package dev.nullkitty.cassette.lexer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import dev.nullkitty.cassette.ast.SourceSpan;

/**
 * A stylesheet decoded into a single {@code char[]}, with CSS Syntax Module Level 3's
 * §3.3 preprocessing already applied.
 *
 * <p>Decoding happens once, upfront, for the whole input. There is no streaming decode. Every span
 * produced downstream is a pair of offsets into this buffer, so those offsets are
 * <em>post-preprocessing</em> character offsets, never byte offsets into the original input and
 * never offsets into the pre-normalization text. A source containing CRLF or a BOM will not agree
 * with its own bytes on where anything is.
 *
 * <h2>The base offset</h2>
 *
 * <p>A text carries the offset at which it sits in whatever coordinate space its caller is
 * laying out, which for an ordinary parse is zero. A caller assembling several sources into one
 * tree gives each the offset it starts at, and every span downstream is then <em>born global</em>.
 * Nothing is rewritten afterwards, so assembling N sources costs N parses and no rebase.
 *
 * <p>The base is not an index into this buffer. {@link #charAt} and {@link #buffer} are local and
 * zero-based, and stay that way. The base is added exactly where a span is constructed, which for
 * a token is {@code TokenBuffer.packedSpan} and for the two spans no token produces is
 * {@link #unresolvedCharsetSpan} and {@code TokenBuffer.packedSpanOfSource}. Adding it anywhere
 * else would corrupt an array index.
 *
 * <p>Implements {@link CharSequence} so a {@link dev.nullkitty.cassette.ast.SourceSpan} can slice
 * it directly, with the same caveat {@link dev.nullkitty.cassette.ast.SourceSpan#text} carries: at
 * a non-zero base a span's offsets are not offsets into this text.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-preprocessing">CSS Syntax Level 3 §3.3</a>
 */
public final class SourceText implements CharSequence {

    /**
     * Bytes scanned for an {@code @charset} rule before giving up.
     */
    private static final int CHARSET_SNIFF_LIMIT = 1024;

    private static final byte[] CHARSET_PREFIX = { '@', 'c', 'h', 'a', 'r', 's', 'e', 't', ' ', '"' };

    private static final byte[] BOM_UTF_8    = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
    private static final byte[] BOM_UTF_16BE = { (byte) 0xFE, (byte) 0xFF };
    private static final byte[] BOM_UTF_16LE = { (byte) 0xFF, (byte) 0xFE };

    private final char[] chars;

    private final int length;

    private final CssEncoding encoding;

    private final String unresolvedCharset;

    private final int unresolvedCharsetLength;

    private final int base;

    private SourceText(char[] chars,
                       int length,
                       CssEncoding encoding,
                       String unresolvedCharset,
                       int unresolvedCharsetLength,
                       int base) {
        this.chars = chars;
        this.length = length;
        this.encoding = encoding;
        this.unresolvedCharset = unresolvedCharset;
        this.unresolvedCharsetLength = unresolvedCharsetLength;
        this.base = base;
    }

    /**
     * Decodes a stylesheet with no externally supplied encoding.
     *
     * @param bytes the raw stylesheet
     * @return the decoded, preprocessed text
     */
    public static SourceText decode(byte[] bytes) {
        return decode(bytes, null);
    }

    /**
     * Decodes a stylesheet, honouring CSS Syntax §3.2.
     *
     * <p>Precedence, highest first: a byte order mark; an {@code @charset} rule at the very
     * start of the input; {@code protocolEncoding}; UTF-8. The BOM outranks {@code @charset}
     * because §3.2 hands the fallback encoding to the Encoding Standard's decode algorithm,
     * and that algorithm sniffs a BOM before it looks at what it was passed.
     *
     * @param bytes the raw stylesheet
     * @param protocolEncoding the encoding the transport claimed, or {@code null}; a
     *        {@code Content-Type} charset parameter or an HTML {@code <link charset>}
     * @return the decoded, preprocessed text
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-byte-stream">CSS Syntax Level 3 §3.2</a>
     * @see <a href="https://encoding.spec.whatwg.org/#decode">WHATWG Encoding Standard §6 Hooks for
     *      standards, decode</a>
     */
    public static SourceText decode(byte[] bytes, //
                                    Charset protocolEncoding) {
        return decode(bytes, protocolEncoding, 0);
    }

    /**
     * Decodes a stylesheet that sits at {@code base} in a larger coordinate space.
     *
     * <p>Everything {@link #decode(byte[], Charset)} does, plus the offset every span this text
     * produces is shifted by. Callers laying out several sources allocate bases from a running
     * cursor: a source's base is the sum of the decoded lengths of everything before it.
     *
     * <p>Lengths here are decoded characters, not bytes. A BOM has been stripped and §3.3 has
     * collapsed CRLF, so a segment's width in this space is {@link #length()} and is not the size
     * of the file. Computing the next base from a byte count misaligns every source after it.
     *
     * @param bytes the raw stylesheet
     * @param protocolEncoding the encoding the transport claimed, or {@code null}
     * @param base the offset this source starts at; zero for a stylesheet parsed on its own
     * @return the decoded, preprocessed text
     * @throws IllegalArgumentException if {@code base} is negative
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-preprocessing">CSS Syntax Level 3 §3.3</a>
     */
    public static SourceText decode(byte[] bytes, //
                                    Charset protocolEncoding,
                                    int base) {
        checkBase(base);
        int bomLength = bomLength(bytes);
        String declaredLabel = bomLength > 0 ? null : sniffCharsetLabel(bytes);
        CssEncoding declared = declaredLabel == null ? null : resolveDeclared(declaredLabel);
        CssEncoding encoding = determineEncoding(bytes, bomLength, protocolEncoding, declared);
        return decodeWith(bytes, bomLength, encoding, unresolvedLabel(declaredLabel, declared), base);
    }

    /**
     * The encoding {@link #decode} would settle on, without decoding anything.
     *
     * <p>For a caller that has to pass an encoding <em>down</em>: CSS Syntax §3.2 makes the
     * fallback chain a byte order mark, then {@code @charset}, then the environment encoding,
     * then UTF-8, and for a sheet reached through an {@code @import} the environment encoding
     * is the encoding of the sheet that imported it. Answering that needs the parent's resolved
     * encoding before the child is decoded.
     *
     * <p>The work is a byte order mark check and a sniff of at most a kilobyte, so running it
     * here and again inside {@code decode} is cheaper than threading the answer back out of
     * every decode that never needed it.
     *
     * @param bytes the raw stylesheet
     * @param protocolEncoding the encoding the transport claimed, or {@code null}
     * @return the encoding it would be decoded with
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-byte-stream">CSS Syntax Level 3 §3.2</a>
     */
    public static CssEncoding detectEncoding(byte[] bytes, //
                                             Charset protocolEncoding) {
        int bomLength = bomLength(bytes);
        String declaredLabel = bomLength > 0 ? null : sniffCharsetLabel(bytes);
        CssEncoding declared = declaredLabel == null ? null : resolveDeclared(declaredLabel);

        return determineEncoding(bytes, bomLength, protocolEncoding, declared);
    }

    /**
     * The label a stylesheet declared and nothing could resolve, which is the one case worth
     * telling the caller about: the fallback is otherwise indistinguishable from a file that
     * declared nothing at all, and decoding with the wrong charset corrupts silently.
     */
    private static String unresolvedLabel(String declaredLabel, CssEncoding declared) {
        return declaredLabel != null && declared == null ? declaredLabel : null;
    }

    /**
     * Reads a stream fully, then decodes it.
     *
     * <p>The stream is consumed but not closed, closing belongs to whoever opened it.
     *
     * @param in the stylesheet bytes
     * @param protocolEncoding the encoding the transport claimed, or {@code null}
     * @return the decoded, preprocessed text
     * @throws IOException if reading fails
     */
    public static SourceText decode(InputStream in, Charset protocolEncoding) throws IOException {
        return decode(in.readAllBytes(), protocolEncoding);
    }

    /**
     * Wraps already-decoded text, skipping charset detection entirely.
     *
     * <p>Preprocessing still runs, newline normalization and NULL replacement are part of
     * the grammar, not of decoding.
     *
     * @param text the stylesheet source
     * @return the preprocessed text
     */
    public static SourceText of(CharSequence text) {
        return of(text, 0);
    }

    /**
     * Wraps already-decoded text sitting at {@code base} in a larger coordinate space.
     *
     * @param text the stylesheet source
     * @param base the offset this source starts at; zero for a stylesheet parsed on its own
     * @return the preprocessed text
     * @throws IllegalArgumentException if {@code base} is negative
     */
    public static SourceText of(CharSequence text, int base) {
        checkBase(base);
        char[] buffer = new char[text.length()];
        for (int i = 0; i < text.length(); i++) {
            buffer[i] = text.charAt(i);
        }

        return new SourceText(buffer, preprocess(buffer, buffer.length), CssEncoding.UTF_8, null, 0, base);
    }

    private static void checkBase(int base) {
        if (base < 0) {
            throw new IllegalArgumentException("base must not be negative: " + base);
        }
    }

    /**
     * Applies §3.2's fallback-encoding rules; the BOM and the {@code @charset} rule have
     * already been checked by the caller.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-byte-stream">CSS Syntax Level 3 §3.2</a>
     */
    private static CssEncoding determineEncoding(byte[] bytes,
                                                 int bomLength,
                                                 Charset protocolEncoding,
                                                 CssEncoding declared) {
        if (bomLength > 0) {
            return switch (bomLength) {
                case 3 -> CssEncoding.UTF_8;
                case 2 -> bytes[0] == BOM_UTF_16BE[0] ? CssEncoding.UTF_16BE : CssEncoding.UTF_16LE;
                default -> CssEncoding.UTF_8;
            };
        }

        if (declared != null) {
            return declared;
        }

        if (protocolEncoding != null) {
            return CssEncoding.of(protocolEncoding);
        }

        return CssEncoding.UTF_8;
    }

    /**
     * Resolves a sniffed label, applying §3.2's UTF-16 contradiction rule.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-byte-stream">CSS Syntax Level 3 §3.2</a>
     */
    private static CssEncoding resolveDeclared(String label) {
        CssEncoding encoding = CssEncoding.forLabel(label);
        if (encoding == null) {
            return null;
        }

        // §3.2: a stylesheet claiming UTF-16 in ASCII-compatible bytes has contradicted
        // itself; those bytes cannot be UTF-16, or the rule would not have matched.
        if (encoding == CssEncoding.UTF_16BE || encoding == CssEncoding.UTF_16LE) {
            return CssEncoding.UTF_8;
        }

        return encoding;
    }

    private static int bomLength(byte[] bytes) {
        if (startsWith(bytes, BOM_UTF_8)) {
            return BOM_UTF_8.length;
        }

        if (startsWith(bytes, BOM_UTF_16BE) || startsWith(bytes, BOM_UTF_16LE)) {
            return 2;
        }

        return 0;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }

        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * Looks for a literal {@code @charset "..."} at byte offset zero.
     *
     * <p>A byte comparison rather than a parse, since the encoding is not known yet and there
     * is nothing to parse with. The rule must have exactly this shape, one space, a double quote
     * and no comment before it, which is why a stylesheet's {@code @charset} is not the same
     * thing as its first at-rule.
     *
     * <p>Returns the label text rather than the encoding, so that a rule naming an encoding
     * nothing can resolve stays distinguishable from no rule at all.
     *
     * @return the declared label, or {@code null} if there is no well-formed rule here
     */
    private static String sniffCharsetLabel(byte[] bytes) {
        if (!startsWith(bytes, CHARSET_PREFIX)) {
            return null;
        }

        int limit = Math.min(bytes.length, CHARSET_SNIFF_LIMIT);
        for (int i = CHARSET_PREFIX.length; i < limit; i++) {
            if (bytes[i] == '\n' || bytes[i] == '\r' || bytes[i] == '\f') {
                return null;
            }

            if (bytes[i] != '"') {
                continue;
            }

            if (i + 1 >= bytes.length || bytes[i + 1] != ';') {
                return null;
            }

            return new String(bytes, CHARSET_PREFIX.length, i - CHARSET_PREFIX.length, StandardCharsets.US_ASCII);
        }

        return null;
    }

    private static SourceText decodeWith(byte[] bytes,
                                         int bomLength,
                                         CssEncoding encoding,
                                         String unresolvedCharset,
                                         int base) {
        char[] buffer = switch (encoding.kind()) {
            case REPLACEMENT -> new char[] { CodePoints.REPLACEMENT };
            case X_USER_DEFINED -> decodeUserDefined(bytes, bomLength);
            case CHARSET -> decodeCharset(bytes, bomLength, encoding.charset());
        };

        int length = preprocess(buffer, buffer.length);

        return new SourceText(buffer,
                              length,
                              encoding,
                              unresolvedCharset,
                              charsetRuleLength(unresolvedCharset, length),
                              base);
    }

    /**
     * The decoded length of the {@code @charset} rule, for the diagnostic to point at.
     *
     * <p>The rule is {@code @charset "<label>";} in ASCII bytes; the sniff only matches
     * those, and it is only reached when no BOM was found, so byte offsets and character
     * offsets agree over it. Clamped anyway: an ASCII-compatible prefix decoded under a
     * protocol-supplied UTF-16 produces a buffer shorter than the rule, and a span past the
     * end of the text would break the invariant that every span lies inside its input.
     */
    private static int charsetRuleLength(String unresolvedCharset, //
                                         int textLength) {
        if (unresolvedCharset == null) {
            return 0;
        }

        return Math.min(CHARSET_PREFIX.length + unresolvedCharset.length() + 2, textLength);
    }

    private static char[] decodeCharset(byte[] bytes, int bomLength, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder() //
                                        .onMalformedInput(CodingErrorAction.REPLACE) //
                                        .onUnmappableCharacter(CodingErrorAction.REPLACE);

        ByteBuffer in = ByteBuffer.wrap(bytes, bomLength, bytes.length - bomLength);
        try {
            CharBuffer out = decoder.decode(in);
            char[] buffer = new char[out.remaining()];
            out.get(buffer);
            return buffer;
        }
        catch (CharacterCodingException e) {
            // REPLACE on both actions means the decoder has nothing left to report.
            throw new AssertionError("replacing decoder reported a coding error", e);
        }
    }

    private static char[] decodeUserDefined(byte[] bytes, //
                                            int bomLength) {
        char[] buffer = new char[bytes.length - bomLength];

        for (int i = 0; i < buffer.length; i++) {
            int b = bytes[bomLength + i] & 0xFF;
            buffer[i] = b < 0x80 ? (char) b : (char) (0xF780 + b - 0x80);
        }

        return buffer;
    }

    /**
     * CSS Syntax §3.3, in place: CRLF, CR and FF all become LF; NULL and any unpaired
     * surrogate becomes U+FFFD.
     *
     * <p>Collapsing CRLF is the only rule that shortens the buffer, so this returns a new
     * length rather than rewriting in place.
     *
     * @return the length after normalization; the tail of {@code buffer} beyond it is stale
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-preprocessing">CSS Syntax Level 3 §3.3</a>
     */
    private static int preprocess(char[] buffer, int length) {
        int write = 0;

        for (int read = 0; read < length; read++) {
            char c = buffer[read];
            switch (c) {
                case '\r' -> {
                    if (read + 1 < length && buffer[read + 1] == '\n') {
                        read++;
                    }

                    buffer[write++] = '\n';
                }

                case '\f' -> buffer[write++] = '\n';

                case '\0' -> buffer[write++] = CodePoints.REPLACEMENT;

                default -> {
                    if (Character.isHighSurrogate(c)) {
                        if (read + 1 < length && Character.isLowSurrogate(buffer[read + 1])) {
                            buffer[write++] = c;
                            buffer[write++] = buffer[++read];
                        }
                        else {
                            buffer[write++] = CodePoints.REPLACEMENT;
                        }
                    }
                    else if (Character.isLowSurrogate(c)) {
                        buffer[write++] = CodePoints.REPLACEMENT;
                    }
                    else {
                        buffer[write++] = c;
                    }
                }
            }
        }

        return write;
    }

    /**
     * The encoding the input was decoded with, after detection.
     */
    public CssEncoding encoding() {
        return this.encoding;
    }

    /**
     * The {@code @charset} label this input declared and nothing could resolve.
     *
     * <p>Non-null means the text above was decoded with {@link #encoding()} instead of with
     * what the stylesheet asked for. That is a fallback the caller cannot otherwise detect,
     * and, for a legacy CJK encoding in particular, one that corrupts rather than garbles:
     * a trailing {@code 0x5C} byte survives a UTF-8 decode as a real backslash, which starts
     * a CSS escape and can swallow a string's closing quote.
     *
     * @return the unresolvable label, or {@code null} if none was declared or it resolved
     */
    public String unresolvedCharset() {
        return this.unresolvedCharset;
    }

    /**
     * Where the unresolvable {@code @charset} rule sits, for a diagnostic to point at.
     *
     * <p>The rule is always at the very start of its own source, since the sniff matches
     * nothing else, so this is the {@linkplain #decode(byte[], Charset, int) base} plus the
     * rule's length. The base is applied here because this is one of the two spans no token
     * produces, which the tokenizer's own base handling does not reach. A span at zero would
     * point every source's warning into whichever file sits at offset 0 of a bundle.
     *
     * @return the rule's span, or an empty span at the base when {@link #unresolvedCharset()}
     *         is null
     */
    public SourceSpan unresolvedCharsetSpan() {
        return new SourceSpan(this.base, this.unresolvedCharsetLength);
    }

    /**
     * Where this text starts in its caller's coordinate space.
     *
     * <p>Zero for an ordinary parse. Never an index into {@link #buffer()}, which is always
     * local, see the class comment.
     *
     * @return the base offset
     */
    int base() {
        return this.base;
    }

    /**
     * The decoded buffer itself, for the tokenizer's inner loop.
     *
     * <p>Not defensively copied; this is the hot path, and the lexer package is not
     * exported. Do not write to it.
     *
     * @return the backing buffer, valid up to {@link #length()}
     */
    char[] buffer() {
        return this.chars;
    }

    @Override
    public int length() {
        return this.length;
    }

    @Override
    public char charAt(int index) {
        if (index < 0 || index >= this.length) {
            throw new IndexOutOfBoundsException("index " + index + " outside [0, " + this.length + ")");
        }

        return this.chars[index];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        if (start < 0 || end > this.length || start > end) {
            throw new IndexOutOfBoundsException("[" + start + ", " + end + ") outside [0, " + this.length + ")");
        }

        return new String(this.chars, start, end - start);
    }

    @Override
    public String toString() {
        return new String(this.chars, 0, this.length);
    }
}
