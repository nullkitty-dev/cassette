package dev.nullkitty.cassette.lexer;

import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.text.Ascii;

/**
 * A whole stylesheet's tokens, stored as parallel arrays and addressed by index.
 *
 * <p>{@link Tokenizer} is a forward-only cursor describing one token at a time. The parser needs
 * more: CSS Syntax's algorithms reconsume the current token, and the selector grammar backtracks
 * over a rule's prelude. Buffering once and indexing gives both, and keeps the parser free of a
 * lookahead ring buffer.
 *
 * <p>Structure-of-arrays rather than a {@code Token[]}, because allocation rate is a tracked
 * metric and one record per token is the largest allocation a parse would make. Five
 * {@code int}s, a {@code byte} and a {@code double} cost 29 bytes per token with no object
 * header, and no {@code String} exists until someone asks for one.
 *
 * <p>Every array is primitive. Holding {@linkplain #types an ordinal byte} rather than a
 * {@code TokenType} reference saves three bytes per token and, once these arrays become G1
 * humongous objects, a third of the parse.
 *
 * <p>The last entry is always {@link TokenType#EOF}. Out-of-range indices read as EOF too, so a
 * parser that runs off the end sees end-of-input rather than an exception.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#reconsume-the-current-input-token">CSS Syntax Level
 *      3 §5.2 Definitions, reconsume the current input token</a>
 */
public final class TokenBuffer {

    /**
     * {@link TokenType#values()} once, because that method clones its array on every call.
     *
     * <p>Read on every {@link #type(int)}, which is the hottest accessor the parser has.
     */
    private static final TokenType[] TYPES = TokenType.values();

    /**
     * Token count of a stylesheet small enough that growth never happens.
     */
    private static final int INITIAL_CAPACITY = 64;

    /**
     * The arrays are sized for two tokens per five characters, an assumed 2.5 characters per
     * token, below what any real stylesheet reaches.
     *
     * <p>Real CSS runs 3.06 to 4.94 characters per token across twenty-odd stylesheets, 3.89 for
     * Bootstrap 5.3.3, 3.85 for a full Tailwind build and 3.06 for Bootstrap 3. An estimate of one
     * token per four characters lands just under the true count almost every time, and a few
     * percent short costs 140%: the arrays fill, grow by half, and everything scanned so far is
     * copied.
     *
     * <p>This therefore leaves margin rather than tracking the average, since the two directions
     * are not symmetric. Overshooting costs one array that is discarded whole; undershooting
     * copies everything so far. At 2.5 the densest CSS measured here, hand-written, comment-free,
     * short values at 2.95 characters per token, still fits without a growth.
     */
    private static final int CHARS_PER_TOKEN_DENOMINATOR = 5;

    /**
     * See {@link #CHARS_PER_TOKEN_DENOMINATOR}: two tokens per five characters.
     */
    private static final int CHARS_PER_TOKEN_NUMERATOR = 2;

    /**
     * Longest text worth interning.
     *
     * <p>Interning trades a hash of the characters for a chance of not copying them, and the trade
     * worsens with length: long values repeat less and cost more to hash when they miss. Measured
     * on Bootstrap 5.3.3, against no interning:
     *
     * <pre>
     * cap    allocation    time
     *  16        -8.0%    +3.0%
     * 128        -9.4%   +10.0%
     * </pre>
     *
     * <p>Everything from 17 to 128 characters is therefore left alone: 1.4% more allocation for
     * 7% more time. Sixteen also covers CSS property names, {@code background-color} being exactly
     * that long, so the strings that repeat most stay in.
     */
    private static final int MAX_INTERNED_LENGTH = 16;

    /**
     * Characters per distinct string, for sizing the intern table; see {@link #intern}.
     */
    private static final int CHARS_PER_DISTINCT_STRING = 32;

    private static final int MIN_INTERN_SLOTS = 256;

    private static final int MAX_INTERN_SLOTS = 1 << 17;

    private final SourceText source;

    private final char[] input;

    /**
     * {@link SourceText#base()}, hoisted: it is read once per span built.
     */
    private final int base;

    /**
     * Token types as {@link TokenType#ordinal()}, not as references.
     *
     * <p>A {@code byte} rather than a compressed-oops reference saves three of the thirty-two
     * bytes this buffer spends per token, and the size is the smaller half of the reason. A
     * reference array would be the only one here, so writing a token would dirty a card, and on a
     * stylesheet large enough for the array to be a G1 humongous object it would sit in old
     * generation and be scanned by every young collection. A {@code byte[]} holds no references
     * for a collector to care about.
     *
     * <p>Twenty-six constants, so the ordinal fits with room to spare; {@link #TYPES} maps back.
     */
    private byte[] types;

    private int[] starts;

    private int[] ends;

    private int[] valueStarts;

    private int[] valueEnds;

    private int[] flags;

    /**
     * Where the {@link #numbers} index starts inside a {@code flags} word.
     *
     * <p>{@code Tokenizer} defines six flag bits, 0 through 5, and {@link #flagSet} masks against
     * those low constants, so it is unaffected by anything stored above them. That leaves 26 bits,
     * an index for 67 million numeric tokens against the 86 thousand a 3.6 MB stylesheet produces.
     *
     * <p>Packing rather than a parallel {@code int[]} is what makes the dense array free. An index
     * array would itself cost four bytes per token, giving back only half of the eight this saves.
     */
    private static final int NUMBER_INDEX_SHIFT = 6;

    /**
     * The largest index the spare bits can hold.
     */
    private static final int MAX_NUMBER_INDEX = (1 << (32 - NUMBER_INDEX_SHIFT)) - 1;

    /**
     * Which {@link TokenType} ordinals carry a number, as a bit per ordinal.
     *
     * <p>{@code append} asks this of every token, more often than anything else here.
     * {@link TokenType#isNumeric()} answers with three reference comparisons against static
     * fields. A shift and a mask against an ordinal already in a register is two instructions and
     * no memory access, and measured 3% of a Bootstrap parse.
     */
    private static final long NUMERIC_TYPES = (1L << TokenType.NUMBER.ordinal())
                                              | (1L << TokenType.PERCENTAGE.ordinal())
                                              | (1L << TokenType.DIMENSION.ordinal());

    /**
     * Numeric values, densely packed: one slot per numeric token rather than per token.
     *
     * <p>Only 5–9% of the tokens in real CSS carry a number, 86,314 of 946,206 in the LARGE
     * corpus entry, so a slot per token would spend eight bytes storing nothing for nine tokens in
     * ten. At 3.2 bytes per source character that is the largest of these arrays and the first to
     * cross G1's humongous threshold, at roughly 650 kB of input.
     *
     * <p>Which numeric token owns which slot lives in the spare bits of {@link #flags} rather than
     * in an index array of its own, so this costs no per-token memory. See
     * {@link #NUMBER_INDEX_SHIFT}.
     *
     * <p>{@code append} writes it for every token and not only for numeric ones, so the slot at the
     * cursor holds whatever the last non-numeric token reported until a real number overwrites it.
     * Nothing reads it, because a non-numeric token's index is zero.
     */
    private double[] numbers;

    /**
     * How many slots of {@link #numbers} are in use, and the next index to hand out.
     *
     * <p>Starts at one, with slot zero reserved holding 0.0, which is what lets
     * {@link #numericValue} keep its "zero for a non-numeric token" contract without asking what
     * type the token is: a token that stored no number has no index packed into its flags, so the
     * index reads as zero and finds the reserved slot. Checking the type instead costs an array
     * load and three reference comparisons in the accessor the parser reads most, measured at
     * +3.6% on a Bootstrap parse.
     */
    private int numberCount = 1;

    private int size;

    /**
     * Open-addressed intern table; {@code null} is an empty slot. See {@link #intern}.
     */
    private String[] interned;

    private int internedCount;

    private TokenBuffer(SourceText source, //
                        int capacity) {
        this.source = source;
        this.input = source.buffer();
        this.base = source.base();

        this.interned = new String[internSlotsFor(source.length())];

        this.types = new byte[capacity];
        this.starts = new int[capacity];
        this.ends = new int[capacity];
        this.valueStarts = new int[capacity];
        this.valueEnds = new int[capacity];
        this.flags = new int[capacity];
        this.numbers = new double[estimateNumberCapacity(capacity)];
    }

    /**
     * Tokenizes a whole stylesheet.
     *
     * @param source the decoded stylesheet
     * @return every token, terminated by {@link TokenType#EOF}
     */
    public static TokenBuffer tokenize(SourceText source) {
        TokenBuffer buffer = new TokenBuffer(source, estimateCapacity(source.length()));
        Tokenizer tokenizer = new Tokenizer(source);

        while (true) {
            TokenType type = tokenizer.next();
            buffer.append(tokenizer, type);

            if (type == TokenType.EOF) {
                return buffer;
            }
        }
    }

    /**
     * How many tokens the arrays are sized for before anything is scanned.
     *
     * <p>Package-private so {@code TokenBufferTest} can assert that a real stylesheet never
     * exceeds it. Growth copies everything scanned so far, which
     * {@link #CHARS_PER_TOKEN_DENOMINATOR} is sized to avoid.
     */
    static int estimateCapacity(int sourceLength) {
        // Dividing first keeps the multiply from overflowing on a very large stylesheet.
        int estimate = sourceLength / CHARS_PER_TOKEN_DENOMINATOR * CHARS_PER_TOKEN_NUMERATOR;
        return Math.max(INITIAL_CAPACITY, estimate);
    }

    /**
     * How many numeric values the dense array is sized for, given a token capacity.
     *
     * <p>An eighth, against the 5–9% of tokens that carry a number across the corpus, so roughly
     * double the measured need. Same reasoning as the token capacity: overshooting wastes one small
     * array, undershooting copies every number so far.
     *
     * <p>Package-private so the sizing test can assert a real stylesheet never grows it.
     * Comparing the count against the capacity cannot fail, since growth keeps the second above
     * the first by construction.
     */
    static int estimateNumberCapacity(int tokenCapacity) {
        return Math.max(INITIAL_CAPACITY, tokenCapacity / 8);
    }

    /**
     * Slots the dense number array holds, for the sizing test.
     */
    int numberCapacity() {
        return this.numbers.length;
    }

    /**
     * Numeric tokens seen, for the sizing test.
     */
    int numberCount() {
        return this.numberCount;
    }

    /**
     * Whether the numeric mask claims this type carries a number, for the mask test.
     */
    static boolean numericMaskHas(TokenType type) {
        return (NUMERIC_TYPES >>> type.ordinal() & 1) != 0;
    }

    /**
     * Where the number index starts in a flags word, for the flag-budget test.
     */
    static int numberIndexShift() {
        return NUMBER_INDEX_SHIFT;
    }

    /**
     * Current array length, for the sizing test; equals the estimate unless growth ran.
     */
    int capacity() {
        return this.types.length;
    }

    private void append(Tokenizer tokenizer, //
                        TokenType type) {
        if (this.size == this.types.length) {
            grow();
        }

        int at = this.size++;
        int ordinal = type.ordinal();
        this.types[at] = (byte) ordinal;
        this.starts[at] = tokenizer.start();
        this.ends[at] = tokenizer.end();
        this.valueStarts[at] = tokenizer.valueStart();
        this.valueEnds[at] = tokenizer.valueEnd();

        // Branchless, and that is the point rather than cleverness for its own sake.
        // Testing whether this token carries a number and skipping the store if not costs a
        // branch on every token, which measured 3% of a Bootstrap parse, more than the store
        // it saves.
        // So the value is written unconditionally at the dense cursor and the cursor advances
        // only for a numeric token, leaving a non-numeric token's write to be overwritten by
        // the next number. The index is masked into the flags the same way: -1 for a numeric
        // token, 0 otherwise.
        int numeric = (int) (NUMERIC_TYPES >>> ordinal) & 1;

        if (this.numberCount == this.numbers.length) {
            growNumbers();
        }

        this.numbers[this.numberCount] = tokenizer.numericValue();
        this.flags[at] = tokenizer.flags() | ((this.numberCount << NUMBER_INDEX_SHIFT) & -numeric);
        this.numberCount += numeric;
    }

    private void growNumbers() {
        int capacity = this.numbers.length + (this.numbers.length >> 1);
        if (capacity > MAX_NUMBER_INDEX) {
            // Unreachable below roughly 700 MB of numeric-dense CSS. Checked here rather than
            // per token because silently packing an index that overflows into the flag bits
            // would corrupt every flag on the token, and this is where the bound can be hit.
            throw new IllegalStateException("more than " + MAX_NUMBER_INDEX + " numeric tokens in one stylesheet");
        }

        this.numbers = java.util.Arrays.copyOf(this.numbers, capacity);
    }

    // -----------------------------------------------------------------------
    // Interning
    // -----------------------------------------------------------------------

    /**
     * Returns the one instance of this text held by this buffer, creating it on first sight.
     *
     * <p>Real stylesheets repeat themselves in exactly the strings the AST keeps: across Bootstrap
     * 5.3.3 the tree holds 20,990 strings of 3,247 distinct texts, and across a full Tailwind
     * build 299,192 of 39,837, 87% and 85% duplicates. Interning takes about a quarter off the
     * tree's retained footprint and 8% off what a parse allocates.
     *
     * <p>It costs time. Allocation in a young generation is a pointer bump, while a hit here
     * costs a hash and a comparison, two passes over the characters against one pass and a bump:
     * 3% of parse time on Bootstrap, and nothing measurable on a file the size of Tailwind.
     * {@link #MAX_INTERNED_LENGTH} bounds it.
     *
     * <p>The table lives on the buffer, so it is scoped to one parse. Nothing is shared between
     * calls, there is no lifetime to manage and nothing to synchronize. This is not
     * {@link String#intern()}, which would put every identifier in a stylesheet into a JVM-wide
     * table for the life of the process.
     *
     * <p>Two equal strings usually come back identical, and no caller may rely on it. Nothing
     * above the length cap is interned, so identity is an optimization rather than a promise.
     *
     * @param from index of the first character
     * @param to   index one past the last character
     * @return the shared instance
     */
    private String intern(int from, int to) {
        int length = to - from;
        if (length == 0) {
            return "";
        }

        if (length > MAX_INTERNED_LENGTH) {
            return new String(this.input, from, length);
        }

        int hash = hashOf(this.input, from, to);
        int mask = this.interned.length - 1;
        for (int slot = hash & mask;; slot = (slot + 1) & mask) {
            String candidate = this.interned[slot];
            if (candidate == null) {
                return store(slot, new String(this.input, from, length));
            }

            if (candidate.hashCode() == hash && matches(candidate, from, length)) {
                return candidate;
            }
        }
    }

    /**
     * The same, for text that had to be materialized before it could be compared, such as a value
     * whose escapes were resolved.
     *
     * @param value the text to intern
     * @return the shared instance, which may be {@code value} itself
     */
    private String intern(String value) {
        if (value.isEmpty() || value.length() > MAX_INTERNED_LENGTH) {
            return value;
        }

        int hash = value.hashCode();
        int mask = this.interned.length - 1;
        for (int slot = hash & mask;; slot = (slot + 1) & mask) {
            String candidate = this.interned[slot];
            if (candidate == null) {
                return store(slot, value);
            }

            if (candidate.hashCode() == hash && candidate.equals(value)) {
                return candidate;
            }
        }
    }

    private String store(int slot, String value) {
        this.interned[slot] = value;
        this.internedCount++;

        // Three quarters full: probe chains stop being short.
        if (this.internedCount > this.interned.length - (this.interned.length >> 2)) {
            rehash();
        }

        return value;
    }

    private void rehash() {
        String[] larger = new String[this.interned.length << 1];
        int mask = larger.length - 1;

        for (String value : this.interned) {
            if (value == null) {
                continue;
            }

            int slot = value.hashCode() & mask;
            while (larger[slot] != null) {
                slot = (slot + 1) & mask;
            }

            larger[slot] = value;
        }

        this.interned = larger;
    }

    /**
     * {@link String#hashCode()}'s algorithm, so a cached hash can reject a candidate.
     */
    private static int hashOf(char[] input, //
                              int from,
                              int to) {
        int hash = 0;
        for (int at = from; at < to; at++) {
            hash = 31 * hash + input[at];
        }

        return hash;
    }

    private boolean matches(String candidate, //
                            int from,
                            int length) {
        if (candidate.length() != length) {
            return false;
        }

        for (int at = 0; at < length; at++) {
            if (candidate.charAt(at) != this.input[from + at]) {
                return false;
            }
        }

        return true;
    }

    /**
     * Table size for a source of this length, rounded up to a power of two.
     *
     * <p>The corpus shows one distinct string per 32 characters, 3,247 in 281 kB and 39,837 in
     * 3.6 MB, and the table holds that without rehashing. The cap makes a very large stylesheet
     * pay a rehash or two rather than take an array bigger than the tree it is helping.
     */
    private static int internSlotsFor(int sourceLength) {
        int wanted = Math.max(MIN_INTERN_SLOTS, sourceLength / CHARS_PER_DISTINCT_STRING);
        int slots = Integer.highestOneBit(Math.min(wanted, MAX_INTERN_SLOTS) - 1) << 1;
        return Math.max(MIN_INTERN_SLOTS, slots);
    }

    private void grow() {
        int capacity = this.types.length + (this.types.length >> 1);

        this.types = java.util.Arrays.copyOf(this.types, capacity);
        this.starts = java.util.Arrays.copyOf(this.starts, capacity);
        this.ends = java.util.Arrays.copyOf(this.ends, capacity);
        this.valueStarts = java.util.Arrays.copyOf(this.valueStarts, capacity);
        this.valueEnds = java.util.Arrays.copyOf(this.valueEnds, capacity);
        this.flags = java.util.Arrays.copyOf(this.flags, capacity);

        // numbers is deliberately absent: it is indexed by numeric-token count, not by token
        // index, and grows in appendNumber on its own schedule.
    }

    /**
     * The number of tokens, including the trailing {@link TokenType#EOF}.
     *
     * @return the token count, always at least one
     */
    public int size() {
        return this.size;
    }

    /**
     * Index of the terminating {@link TokenType#EOF} entry.
     */
    public int eofIndex() {
        return this.size - 1;
    }

    /**
     * The type of token {@code at}.
     *
     * @param at the token index
     * @return the type, or {@link TokenType#EOF} for any index outside the buffer
     */
    public TokenType type(int at) {
        return inRange(at) ? TYPES[this.types[at]] : TokenType.EOF;
    }

    /**
     * Whether token {@code at} is whitespace or a comment.
     *
     * @param at the token index
     * @return whether the parser may skip it
     */
    public boolean isTrivia(int at) {
        return type(at).isTrivia();
    }

    /**
     * Offset of the token's first character, clamped to end of input past the last token.
     *
     * <p>Local to this buffer, and not shifted by the source's
     * {@linkplain SourceText#base() base}. This and {@link #end} index the decoded
     * {@code char[]}, and {@link #intern}, {@link #isDelim} and the adjacency check the selector
     * grammar depends on all read characters with them, so a based offset would read the wrong ones
     * or run off the end. The base is added where a <em>span</em> is built, which is
     * {@link #packedSpan} and nowhere else.
     */
    public int start(int at) {
        return inRange(at) ? this.starts[at] : this.source.length();
    }

    /**
     * Offset one past the token's last character; local, like {@link #start}.
     */
    public int end(int at) {
        return inRange(at) ? this.ends[at] : this.source.length();
    }

    /**
     * The text before a token's semantic value.
     *
     * <p>For a {@code DIMENSION} this is the number, since the value is the unit; for a
     * {@code PERCENTAGE} the value is already the number and this is empty.
     *
     * @param at the token index
     * @return the characters between the token's start and the start of its value
     */
    public String prefix(int at) {
        if (!inRange(at)) {
            return "";
        }

        return intern(this.starts[at], this.valueStarts[at]);
    }

    /**
     * The token's full extent, delimiters and all.
     *
     * @param at the token index
     * @return the span
     */
    public SourceSpan span(int at) {
        return SourceSpan.unpack(packedSpan(at));
    }

    /**
     * The span covering tokens {@code from} through {@code to} exclusive.
     *
     * @param from index of the first token
     * @param to   index one past the last token
     * @return the covering span, zero-width at {@code from} when the range is empty
     */
    public SourceSpan span(int from, int to) {
        return SourceSpan.unpack(packedSpan(from, to));
    }

    /**
     * {@link #span(int)} in the packed form an AST node stores.
     *
     * <p>Building nodes goes through this rather than through {@link #span(int)}, because there is
     * one span per node and materializing each one only to pack it into the node is an allocation
     * per token that nothing reads.
     *
     * <p>This is where a span becomes global. Every span in a tree is built here or by
     * {@link #packedSpanOfSource}, so adding the source's {@linkplain SourceText#base() base} at
     * these two points is the whole of "spans are born global". Nothing downstream rebases, and a
     * tree assembled from several sources costs one parse per source and no rewrite.
     *
     * @param at the token index
     * @return the packed span
     */
    public long packedSpan(int at) {
        int from = start(at);
        return SourceSpan.pack(this.base + from, end(at) - from);
    }

    /**
     * {@link #span(int, int)} in the packed form an AST node stores.
     *
     * @param from index of the first token
     * @param to   index one past the last token
     * @return the packed covering span, zero-width at {@code from} when the range is empty
     */
    public long packedSpan(int from, //
                           int to) {
        if (to <= from) {
            return SourceSpan.pack(this.base + start(from), 0);
        }

        int begin = start(from);

        return SourceSpan.pack(this.base + begin, end(to - 1) - begin);
    }

    /**
     * The span covering the whole source, for the {@code Stylesheet} that holds everything in
     * it.
     *
     * <p>The one span in a tree that no token produces: a stylesheet covers its input whether or
     * not any token was scanned, so it cannot be expressed as a range of tokens.
     * {@link SourceText#unresolvedCharsetSpan()} is the other span built outside the tokenizer, for
     * the same reason, and both carried a hardcoded zero base until the coordinate space existed.
     *
     * @return the packed span of the whole source, based
     */
    public long packedSpanOfSource() {
        return SourceSpan.pack(this.base, this.source.length());
    }

    /**
     * The token's source text, exactly as written.
     *
     * @param at the token index
     * @return the raw text, including quotes, {@code #}, {@code @} and unit
     */
    public String raw(int at) {
        return intern(start(at), end(at));
    }

    /**
     * The token's value with escapes resolved.
     *
     * @param at the token index
     * @return the decoded value; the unit for a dimension, the empty string past end of input
     */
    public String value(int at) {
        if (!inRange(at)) {
            return "";
        }

        int from = this.valueStarts[at];
        int to = this.valueEnds[at];

        if (!hasEscape(at)) {
            return intern(from, to);
        }

        return intern(Escapes.unescape(this.input, from, to));
    }

    /**
     * The token's value with escapes resolved and ASCII letters lowercased.
     *
     * <p>For the many places CSS matches identifiers ASCII case-insensitively: at-rule
     * names, pseudo-class names, {@code !important}.
     *
     * @param at the token index
     * @return the decoded, lowercased value
     */
    public String lowerValue(int at) {
        return Ascii.lower(value(at));
    }

    /**
     * Compares a token's value to an ASCII literal, case-insensitively, without allocating
     * unless the value contains an escape.
     *
     * @param at       the token index
     * @param expected the ASCII literal to compare against
     * @return whether the values match
     */
    public boolean valueEqualsIgnoreCase(int at, //
                                         String expected) {
        if (!inRange(at)) {
            return false;
        }

        return Escapes.equalsIgnoreCase(this.input, this.valueStarts[at], this.valueEnds[at], expected, hasEscape(at));
    }

    /**
     * Whether a token's value starts with an ASCII literal.
     *
     * <p>For the two-character tests the parser makes constantly, {@code --} for a custom
     * property, without materializing the value first.
     *
     * @param at     the token index
     * @param prefix the ASCII literal to test for
     * @return whether the value starts with it
     */
    public boolean valueStartsWith(int at, //
                                   String prefix) {
        if (!inRange(at)) {
            return false;
        }

        if (hasEscape(at)) {
            return value(at).startsWith(prefix);
        }

        int from = this.valueStarts[at];

        if (this.valueEnds[at] - from < prefix.length()) {
            return false;
        }

        for (int i = 0; i < prefix.length(); i++) {
            if (this.input[from + i] != prefix.charAt(i)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Whether token {@code at} is a {@link TokenType#DELIM} of exactly {@code codePoint}.
     *
     * @param at        the token index
     * @param codePoint the delimiter to test for
     * @return whether the token is that delimiter
     */
    public boolean isDelim(int at, //
                           char codePoint) {
        return type(at) == TokenType.DELIM && end(at) - start(at) == 1 && this.input[start(at)] == codePoint;
    }

    /**
     * The code point of a {@link TokenType#DELIM}.
     *
     * @param at the token index
     * @return the delimiter's code point, or -1 for any other token
     */
    public int delimCodePoint(int at) {
        if (type(at) != TokenType.DELIM) {
            return -1;
        }

        return Character.codePointAt(this.input, start(at), end(at));
    }

    /**
     * The numeric value of a {@code NUMBER}, {@code PERCENTAGE} or {@code DIMENSION}.
     *
     * @param at the token index
     * @return the value, or 0 for non-numeric tokens
     */
    public double numericValue(int at) {
        if (!inRange(at)) {
            return 0;
        }
        // No type test: a non-numeric token packed no index, so this reads the reserved slot
        // zero and gets 0.0. See numberCount.
        return this.numbers[this.flags[at] >>> NUMBER_INDEX_SHIFT];
    }

    /**
     * Whether a {@code HASH} token's value is a valid identifier.
     */
    public boolean isIdHash(int at) {
        return flagSet(at, Tokenizer.FLAG_ID);
    }

    /**
     * Whether a numeric token was written without a fractional part or exponent.
     */
    public boolean isInteger(int at) {
        return flagSet(at, Tokenizer.FLAG_INTEGER);
    }

    /**
     * Whether a numeric token carried an explicit sign.
     */
    public boolean hasSign(int at) {
        return flagSet(at, Tokenizer.FLAG_SIGNED);
    }

    /**
     * Whether a numeric token was written in exponential notation.
     */
    public boolean hasExponent(int at) {
        return flagSet(at, Tokenizer.FLAG_EXPONENT);
    }

    /**
     * Whether the token's value contains escapes, and so differs from its source text.
     */
    public boolean hasEscape(int at) {
        return flagSet(at, Tokenizer.FLAG_ESCAPED);
    }

    /**
     * Whether a string, url or comment was closed before the input ended.
     */
    public boolean isTerminated(int at) {
        return flagSet(at, Tokenizer.FLAG_TERMINATED);
    }

    private boolean flagSet(int at, int flag) {
        return inRange(at) && (this.flags[at] & flag) != 0;
    }

    private boolean inRange(int at) {
        return at >= 0 && at < this.size;
    }
}
