package dev.nullkitty.cassette.lexer;

import static dev.nullkitty.cassette.lexer.CodePoints.EOF;

import dev.nullkitty.cassette.ast.SourceSpan;

/**
 * The CSS Syntax Module Level 3 §4.3 tokenizer, as a cursor over a {@link SourceText}.
 *
 * <p>Advancing does not allocate. The current token lives in fields on this object rather than in
 * a freshly-built record, because allocation rate is the metric this library is tuned against and
 * a per-token object would dominate it. A caller needing a token to outlive the cursor
 * materializes it through {@link #value()} or {@link #span()}; everything else stays as offsets.
 *
 * <pre>{@code
 * Tokenizer tokenizer = new Tokenizer(SourceText.decode(bytes));
 * while (tokenizer.next() != TokenType.EOF) {
 *     if (tokenizer.type() == TokenType.IDENT) { ... }
 * }
 * }</pre>
 *
 * <p>Not thread-safe, and not meant to be: one cursor belongs to one parse.
 *
 * <p>The tokenizer never throws on malformed input. Everything the spec calls a parse error
 * is either absorbed silently or surfaced as {@link TokenType#BAD_STRING} /
 * {@link TokenType#BAD_URL} plus a cleared {@link #isTerminated()} flag. Turning those into
 * diagnostics is the parser's job.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#tokenizer-algorithms">CSS Syntax Level 3 §4.3</a>
 */
public final class Tokenizer {

    /**
     * Set on a {@link TokenType#HASH} whose value is a valid identifier.
     */
    static final int FLAG_ID = 1 << 0;

    /**
     * Set on a numeric token whose value has no fractional part or exponent.
     */
    static final int FLAG_INTEGER = 1 << 1;

    /**
     * Set on a numeric token written with an explicit {@code +} or {@code -}.
     */
    static final int FLAG_SIGNED = 1 << 2;

    /**
     * Set on a numeric token written with an exponent.
     */
    static final int FLAG_EXPONENT = 1 << 3;

    /**
     * Set when the token's value contains an escape and so cannot be read straight off the buffer.
     */
    static final int FLAG_ESCAPED = 1 << 4;

    /**
     * Set when a string, url or comment reached its closing delimiter.
     */
    static final int FLAG_TERMINATED = 1 << 5;

    private final SourceText source;

    private final char[] input;

    private final int length;

    private int position;

    private TokenType type;

    private int start;

    private int end;

    /**
     * Start of the token's semantic value, or -1 when that is the whole token.
     */
    private int valueStart;

    /**
     * End of the token's semantic value, or -1 when that is the whole token.
     */
    private int valueEnd;

    private double numericValue;

    private int flags;

    /**
     * Creates a cursor positioned before the first token.
     *
     * @param source the decoded stylesheet
     */
    public Tokenizer(SourceText source) {
        this.source = source;
        this.input = source.buffer();
        this.length = source.length();
        this.type = null;
    }

    /**
     * The text this cursor reads.
     */
    public SourceText source() {
        return this.source;
    }

    /**
     * Advances to the next token.
     *
     * @return the new current type, {@link TokenType#EOF} once the input is exhausted and
     *         for every call after that
     */
    public TokenType next() {
        this.start = this.position;
        this.valueStart = -1;
        this.valueEnd = -1;
        this.numericValue = 0;
        this.flags = 0;

        this.type = scan();
        this.end = this.position;

        return this.type;
    }

    // -----------------------------------------------------------------------
    // Current token
    // -----------------------------------------------------------------------

    /**
     * The current token's type.
     *
     * @return the type, or {@code null} before the first {@link #next()}
     */
    public TokenType type() {
        return this.type;
    }

    /**
     * Offset of the token's first character.
     */
    public int start() {
        return this.start;
    }

    /**
     * Offset one past the token's last character.
     */
    public int end() {
        return this.end;
    }

    /**
     * The token's full extent, delimiters and all.
     */
    public SourceSpan span() {
        return new SourceSpan(this.start, this.end - this.start);
    }

    /**
     * Offset of the token's semantic value: inside the quotes of a string, after the
     * {@code #} of a hash, the unit of a dimension.
     *
     * @return the offset, equal to {@link #start()} where the whole token is the value
     */
    public int valueStart() {
        return this.valueStart < 0 ? this.start : this.valueStart;
    }

    /**
     * Offset one past the token's semantic value.
     *
     * @return the offset, equal to {@link #end()} where the whole token is the value
     */
    public int valueEnd() {
        return this.valueEnd < 0 ? this.end : this.valueEnd;
    }

    /**
     * The token's semantic value as a span.
     */
    public SourceSpan valueSpan() {
        int from = valueStart();
        return new SourceSpan(from, valueEnd() - from);
    }

    /**
     * The token's source text, exactly as written.
     *
     * @return the raw text, including quotes, {@code #}, {@code @} and unit
     */
    public String raw() {
        return new String(this.input, this.start, this.end - this.start);
    }

    /**
     * The token's value with escapes resolved.
     *
     * <p>Allocates. For {@code IDENT}, {@code FUNCTION}, {@code AT_KEYWORD}, {@code HASH},
     * {@code STRING}, {@code URL} and {@code COMMENT} this is the meaningful content; for a
     * {@code DIMENSION} it is the unit, with the number in {@link #numericValue()}.
     *
     * @return the decoded value
     */
    public String value() {
        int from = valueStart();
        int to = valueEnd();
        if (!hasEscape()) {
            return new String(this.input, from, to - from);
        }

        return unescape(from, to);
    }

    /**
     * Compares the token's value to an ASCII string, case-insensitively, without allocating
     * in the common case.
     *
     * @param expected the ASCII string to compare against
     * @return whether the values match
     */
    public boolean valueEqualsIgnoreCase(String expected) {
        return equalsIgnoreCase(valueStart(), valueEnd(), expected);
    }

    /**
     * The numeric value of a {@code NUMBER}, {@code PERCENTAGE} or {@code DIMENSION}.
     *
     * <p>Computed by §4.3.13's formula rather than by {@code strtod}, so it can differ from
     * {@link Double#parseDouble} in the last bit for inputs with many significant digits.
     * The raw text remains authoritative; this is a derived view for minification decisions.
     *
     * @return the value, or 0 for non-numeric tokens
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#convert-string-to-number">CSS Syntax Level 3
     *      §4.3.13</a>
     */
    public double numericValue() {
        return this.numericValue;
    }

    /**
     * Whether a {@code HASH} token's value is a valid identifier, rather than e.g. {@code #123}.
     */
    public boolean isIdHash() {
        return (this.flags & FLAG_ID) != 0;
    }

    /**
     * Whether a numeric token was written without a fractional part or exponent.
     */
    public boolean isInteger() {
        return (this.flags & FLAG_INTEGER) != 0;
    }

    /**
     * Whether a numeric token carried an explicit sign, which minification may drop.
     */
    public boolean hasSign() {
        return (this.flags & FLAG_SIGNED) != 0;
    }

    /**
     * Whether a numeric token was written in exponential notation.
     */
    public boolean hasExponent() {
        return (this.flags & FLAG_EXPONENT) != 0;
    }

    /**
     * Whether the value contains escapes, and so differs from its source text.
     */
    public boolean hasEscape() {
        return (this.flags & FLAG_ESCAPED) != 0;
    }

    /**
     * Whether a string, url or comment was closed before the input ended.
     *
     * @return {@code false} for an unterminated construct, which the spec treats as a parse
     *         error while still producing a token
     */
    public boolean isTerminated() {
        return (this.flags & FLAG_TERMINATED) != 0;
    }

    /**
     * The current token's flag word, for {@link TokenBuffer} to store verbatim.
     *
     * @return the {@code FLAG_*} bits set on this token
     */
    int flags() {
        return this.flags;
    }

    // -----------------------------------------------------------------------
    // §4.3.1 Consume a token
    // -----------------------------------------------------------------------

    private TokenType scan() {
        int cp = codePointAt(this.position);
        if (cp == EOF) {
            return TokenType.EOF;
        }

        switch (cp) {
            case '/': {
                if (peek(1) == '*') {
                    return consumeComment();
                }

                this.position++;
                return TokenType.DELIM;
            }

            case ' ', '\t', '\n': {
                do {
                    this.position++;
                }
                while (CodePoints.isWhitespace(peek(0)));

                return TokenType.WHITESPACE;
            }

            case '"', '\'': {
                return consumeString(cp);
            }

            case '#': {
                return consumeHash();
            }

            case '(': {
                this.position++;
                return TokenType.LEFT_PAREN;
            }

            case ')': {
                this.position++;
                return TokenType.RIGHT_PAREN;
            }

            case '[': {
                this.position++;
                return TokenType.LEFT_SQUARE;
            }

            case ']': {
                this.position++;
                return TokenType.RIGHT_SQUARE;
            }

            case '{': {
                this.position++;
                return TokenType.LEFT_CURLY;
            }

            case '}': {
                this.position++;
                return TokenType.RIGHT_CURLY;
            }

            case ',': {
                this.position++;
                return TokenType.COMMA;
            }

            case ':': {
                this.position++;
                return TokenType.COLON;
            }

            case ';': {
                this.position++;
                return TokenType.SEMICOLON;
            }

            case '+', '.': {
                if (wouldStartNumber(this.position)) {
                    return consumeNumeric();
                }

                this.position++;
                return TokenType.DELIM;
            }

            case '-': {
                if (wouldStartNumber(this.position)) {
                    return consumeNumeric();
                }

                if (peek(1) == '-' && peek(2) == '>') {
                    this.position += 3;
                    return TokenType.CDC;
                }

                if (wouldStartIdent(this.position)) {
                    return consumeIdentLike();
                }

                this.position++;
                return TokenType.DELIM;
            }

            case '<': {
                if (peek(1) == '!' && peek(2) == '-' && peek(3) == '-') {
                    this.position += 4;
                    return TokenType.CDO;
                }

                this.position++;
                return TokenType.DELIM;
            }

            case '@': {
                return consumeAtKeyword();
            }

            case '\\': {
                if (isValidEscapeAt(this.position)) {
                    return consumeIdentLike();
                }

                this.position++;
                return TokenType.DELIM;
            }

            default: {
                if (CodePoints.isDigit(cp)) {
                    return consumeNumeric();
                }

                if (CodePoints.isIdentStart(cp)) {
                    return consumeIdentLike();
                }

                this.position += Character.charCount(cp);
                return TokenType.DELIM;
            }
        }
    }

    /**
     * Comments, which §4.3.2 consumes and discards. cassette emits them so the serializer
     * can put them back.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-comment">CSS Syntax Level 3 §4.3.2</a>
     */
    private TokenType consumeComment() {
        this.position += 2;
        this.valueStart = this.position;

        while (this.position < this.length) {
            if (this.input[this.position] == '*' && peek(1) == '/') {
                this.valueEnd = this.position;
                this.position += 2;
                this.flags |= FLAG_TERMINATED;
                return TokenType.COMMENT;
            }

            this.position++;
        }

        this.valueEnd = this.position;

        return TokenType.COMMENT;
    }

    /**
     * §4.3.5 Consume a string token.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-string-token">CSS Syntax Level 3
     *      §4.3.5</a>
     */
    private TokenType consumeString(int quote) {
        this.position++;
        this.valueStart = this.position;

        while (true) {
            int cp = codePointAt(this.position);
            if (cp == EOF) {
                this.valueEnd = this.position;
                return TokenType.STRING;
            }

            if (cp == quote) {
                this.valueEnd = this.position;
                this.position++;
                this.flags |= FLAG_TERMINATED;
                return TokenType.STRING;
            }

            if (cp == '\n') {
                // The newline is left unconsumed: it belongs to whatever follows.
                this.valueEnd = this.position;
                return TokenType.BAD_STRING;
            }

            if (cp == '\\') {
                this.flags |= FLAG_ESCAPED;
                if (this.position + 1 >= this.length) {
                    // §4.3.5 says to *do nothing* with a backslash at end of input, where
                    // §4.3.7, which idents, dimensions and urls go through, turns the same
                    // backslash into U+FFFD. Ending the value span before it is what keeps
                    // Escapes from applying the second rule to a string.
                    this.valueEnd = this.position;
                    this.position++;
                    return TokenType.STRING;
                }

                if (this.input[this.position + 1] == '\n') {
                    this.position += 2;
                    continue;
                }

                this.position++;

                consumeEscapedCodePoint();
                continue;
            }

            this.position += Character.charCount(cp);
        }
    }

    /**
     * §4.3.1, the {@code #} branch.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-token">CSS Syntax Level 3 §4.3.1</a>
     */
    private TokenType consumeHash() {
        this.position++;
        int cp = codePointAt(this.position);
        if (cp != EOF && (CodePoints.isIdent(cp) || isValidEscapeAt(this.position))) {
            if (wouldStartIdent(this.position)) {
                this.flags |= FLAG_ID;
            }

            this.valueStart = this.position;

            consumeIdentSequence();

            this.valueEnd = this.position;
            return TokenType.HASH;
        }

        return TokenType.DELIM;
    }

    /**
     * §4.3.1, the {@code @} branch.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-token">CSS Syntax Level 3 §4.3.1</a>
     */
    private TokenType consumeAtKeyword() {
        this.position++;

        if (!wouldStartIdent(this.position)) {
            return TokenType.DELIM;
        }

        this.valueStart = this.position;

        consumeIdentSequence();

        this.valueEnd = this.position;

        return TokenType.AT_KEYWORD;
    }

    /**
     * §4.3.4 Consume an ident-like token.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-ident-like-token">CSS Syntax Level 3
     *      §4.3.4</a>
     */
    private TokenType consumeIdentLike() {
        int identStart = this.position;

        consumeIdentSequence();

        int identEnd = this.position;

        if (peek(0) == '(' && equalsIgnoreCase(identStart, identEnd, "url")) {
            this.position++;

            // Leave one space in place if there are two, so `url( "x" )` still reads as a
            // function whose argument is a string.
            while (CodePoints.isWhitespace(peek(0)) && CodePoints.isWhitespace(peek(1))) {
                this.position++;
            }

            int after = peek(0);
            int afterNext = peek(1);

            boolean quoted = after == '"'
                             || after == '\''
                             || (CodePoints.isWhitespace(after) && (afterNext == '"' || afterNext == '\''));
            if (!quoted) {
                return consumeUrl();
            }

            this.valueStart = identStart;
            this.valueEnd = identEnd;

            return TokenType.FUNCTION;
        }

        this.valueStart = identStart;
        this.valueEnd = identEnd;

        if (peek(0) == '(') {
            this.position++;
            return TokenType.FUNCTION;
        }

        return TokenType.IDENT;
    }

    /**
     * §4.3.6 Consume a url token; {@code url(} has already been consumed.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-url-token">CSS Syntax Level 3 §4.3.6</a>
     */
    private TokenType consumeUrl() {
        while (CodePoints.isWhitespace(peek(0))) {
            this.position++;
        }

        this.valueStart = this.position;

        while (true) {
            int cp = codePointAt(this.position);
            if (cp == ')') {
                this.valueEnd = this.position;
                this.position++;
                this.flags |= FLAG_TERMINATED;
                return TokenType.URL;
            }

            if (cp == EOF) {
                this.valueEnd = this.position;
                return TokenType.URL;
            }

            if (CodePoints.isWhitespace(cp)) {
                this.valueEnd = this.position;

                while (CodePoints.isWhitespace(peek(0))) {
                    this.position++;
                }

                int after = peek(0);
                if (after == ')') {
                    this.position++;
                    this.flags |= FLAG_TERMINATED;
                    return TokenType.URL;
                }

                if (after == EOF) {
                    return TokenType.URL;
                }

                return badUrl();
            }

            if (cp == '"' || cp == '\'' || cp == '(' || CodePoints.isNonPrintable(cp)) {
                return badUrl();
            }

            if (cp == '\\') {
                if (isValidEscapeAt(this.position)) {
                    this.flags |= FLAG_ESCAPED;
                    this.position++;
                    consumeEscapedCodePoint();
                    continue;
                }

                return badUrl();
            }

            this.position += Character.charCount(cp);
        }
    }

    /**
     * §4.3.14 Consume the remnants of a bad url.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-remnants-of-bad-url">CSS Syntax Level 3
     *      §4.3.14</a>
     */
    private TokenType badUrl() {
        while (true) {
            int cp = codePointAt(this.position);
            if (cp == EOF) {
                break;
            }

            if (cp == ')') {
                this.position++;
                break;
            }

            if (isValidEscapeAt(this.position)) {
                this.position++;
                consumeEscapedCodePoint();
                continue;
            }

            this.position += Character.charCount(cp);
        }

        // A bad-url-token has no value; the whole token stands in for it.
        this.valueStart = -1;
        this.valueEnd = -1;
        this.flags &= ~FLAG_ESCAPED;

        return TokenType.BAD_URL;
    }

    /**
     * §4.3.3 Consume a numeric token.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-numeric-token">CSS Syntax Level 3
     *      §4.3.3</a>
     */
    private TokenType consumeNumeric() {
        consumeNumber();

        if (wouldStartIdent(this.position)) {
            int unitStart = this.position;
            consumeIdentSequence();
            this.valueStart = unitStart;
            this.valueEnd = this.position;
            return TokenType.DIMENSION;
        }

        if (peek(0) == '%') {
            this.valueStart = this.start;
            this.valueEnd = this.position;
            this.position++;
            return TokenType.PERCENTAGE;
        }

        return TokenType.NUMBER;
    }

    /**
     * §4.3.12 Consume a number.
     *
     * <p>Accumulates the parts and applies the spec's {@code s·(i + f·10^-d)·10^(t·e)}
     * directly, which both avoids materializing the digits as a {@code String} and is the
     * value the spec defines.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-number">CSS Syntax Level 3 §4.3.12</a>
     */
    private void consumeNumber() {
        boolean integer = true;
        double sign = 1;

        int first = peek(0);
        if (first == '+' || first == '-') {
            this.flags |= FLAG_SIGNED;
            if (first == '-') {
                sign = -1;
            }

            this.position++;
        }

        double integerPart = 0;
        while (CodePoints.isDigit(peek(0))) {
            integerPart = integerPart * 10 + (this.input[this.position] - '0');
            this.position++;
        }

        double fraction = 0;
        int fractionDigits = 0;

        if (peek(0) == '.' && CodePoints.isDigit(peek(1))) {
            integer = false;
            this.position++;
            while (CodePoints.isDigit(peek(0))) {
                fraction = fraction * 10 + (this.input[this.position] - '0');
                fractionDigits++;
                this.position++;
            }
        }

        double exponent = 0;
        double exponentSign = 1;
        int marker = peek(0);
        if (marker == 'e' || marker == 'E') {
            int afterMarker = peek(1);
            boolean signedExponent = afterMarker == '+' || afterMarker == '-';

            if (CodePoints.isDigit(signedExponent ? peek(2) : afterMarker)) {
                integer = false;
                this.flags |= FLAG_EXPONENT;
                this.position++;

                if (signedExponent) {
                    if (afterMarker == '-') {
                        exponentSign = -1;
                    }

                    this.position++;
                }

                while (CodePoints.isDigit(peek(0))) {
                    exponent = exponent * 10 + (this.input[this.position] - '0');
                    this.position++;
                }
            }
        }

        double magnitude = integerPart + fraction * Math.pow(10, -fractionDigits);
        this.numericValue = sign * magnitude * Math.pow(10, exponentSign * exponent);

        if (integer) {
            this.flags |= FLAG_INTEGER;
        }
    }

    /**
     * §4.3.11 Consume an ident sequence.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-name">CSS Syntax Level 3 §4.3.11</a>
     */
    private void consumeIdentSequence() {
        while (true) {
            int cp = codePointAt(this.position);
            if (cp != EOF && CodePoints.isIdent(cp)) {
                this.position += Character.charCount(cp);
                continue;
            }

            if (isValidEscapeAt(this.position)) {
                this.flags |= FLAG_ESCAPED;
                this.position++;
                consumeEscapedCodePoint();
                continue;
            }

            return;
        }
    }

    /**
     * §4.3.7 Consume an escaped code point; the backslash has already been consumed.
     *
     * <p>Only advances the cursor; the resolved code point is produced later by
     * {@link #unescape}, so that scanning stays allocation-free.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-escaped-code-point">CSS Syntax Level 3
     *      §4.3.7</a>
     */
    private void consumeEscapedCodePoint() {
        int cp = codePointAt(this.position);
        if (cp == EOF) {
            return;
        }

        if (CodePoints.isHexDigit(cp)) {
            int digits = 0;
            while (digits < 6 && CodePoints.isHexDigit(peek(0))) {
                this.position++;
                digits++;
            }

            if (CodePoints.isWhitespace(peek(0))) {
                this.position++;
            }

            return;
        }

        this.position += Character.charCount(cp);
    }

    // -----------------------------------------------------------------------
    // §4.3.8-4.3.10 Lookahead predicates
    // -----------------------------------------------------------------------

    /**
     * §4.3.8. A backslash at end of input <em>is</em> a valid escape; the second code point
     * is EOF, which is not a newline, and resolves to U+FFFD.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#starts-with-a-valid-escape">CSS Syntax Level 3
     *      §4.3.8</a>
     */
    private boolean isValidEscapeAt(int at) {
        if (at >= this.length || this.input[at] != '\\') {
            return false;
        }

        return at + 1 >= this.length || this.input[at + 1] != '\n';
    }

    /**
     * §4.3.9 Check if three code points would start an ident sequence.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#would-start-an-identifier">CSS Syntax Level 3
     *      §4.3.9</a>
     */
    private boolean wouldStartIdent(int at) {
        int cp = codePointAt(at);
        if (cp == '-') {
            int next = codePointAt(at + 1);
            return next == '-' || (next != EOF && CodePoints.isIdentStart(next)) || isValidEscapeAt(at + 1);
        }

        if (cp == '\\') {
            return isValidEscapeAt(at);
        }

        return cp != EOF && CodePoints.isIdentStart(cp);
    }

    /**
     * §4.3.10 Check if three code points would start a number.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#starts-with-a-number">CSS Syntax Level 3
     *      §4.3.10</a>
     */
    private boolean wouldStartNumber(int at) {
        int cp = charAt(at);
        if (cp == '+' || cp == '-') {
            return CodePoints.isDigit(charAt(at + 1)) || (charAt(at + 1) == '.' && CodePoints.isDigit(charAt(at + 2)));
        }

        if (cp == '.') {
            return CodePoints.isDigit(charAt(at + 1));
        }

        return CodePoints.isDigit(cp);
    }

    // -----------------------------------------------------------------------
    // Buffer access
    // -----------------------------------------------------------------------

    /**
     * The character {@code offset} ahead of the cursor, or {@link CodePoints#EOF}.
     */
    private int peek(int offset) {
        return charAt(this.position + offset);
    }

    private int charAt(int at) {
        return at >= 0 && at < this.length ? this.input[at] : EOF;
    }

    /**
     * The code point at {@code at}, pairing surrogates so astral characters classify
     * correctly as ident code points.
     */
    private int codePointAt(int at) {
        if (at < 0 || at >= this.length) {
            return EOF;
        }

        char c = this.input[at];
        if (Character.isHighSurrogate(c) && at + 1 < this.length && Character.isLowSurrogate(this.input[at + 1])) {
            return Character.toCodePoint(c, this.input[at + 1]);
        }

        return c;
    }

    /**
     * Resolves the escapes in {@code [from, to)} into their code points.
     */
    private String unescape(int from, int to) {
        return Escapes.unescape(this.input, from, to);
    }

    /**
     * ASCII case-insensitive comparison of {@code [from, to)} against a literal.
     */
    private boolean equalsIgnoreCase(int from, //
                                     int to,
                                     String expected) {
        return Escapes.equalsIgnoreCase(this.input, from, to, expected, hasEscape());
    }
}
