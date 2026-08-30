package dev.nullkitty.cassette.lexer;

/**
 * Code point classification from CSS Syntax Module Level 3, §4.2.
 *
 * <p>Everything here takes a code point rather than a {@code char}, because the ident
 * grammar admits astral characters and those arrive as surrogate pairs in a
 * {@code char[]}.
 *
 * <p>Public so the serializer can share {@link #isIdent} rather than keep a second copy of
 * the predicate. {@code lexer} is not exported, so this widens nothing a consumer can see.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#tokenizer-definitions">CSS Syntax Level 3 §4.2</a>
 */
public final class CodePoints {

    /**
     * Sentinel for "past the end of input". Never a real code point.
     */
    static final int EOF = -1;

    /**
     * U+FFFD, written escaped so the constant survives any re-encoding of this file.
     */
    static final char REPLACEMENT = '\uFFFD';

    /**
     * Largest code point Unicode defines; escapes above it become U+FFFD.
     */
    static final int MAX_CODE_POINT = 0x10FFFF;

    /**
     * The alphabet {@link #hexValue} decodes and every writer in the library spells hex in,
     * lowercase because that is the case CSS and JSON escapes are conventionally written in.
     *
     * <p>Here rather than beside either writer, because both need it and their packages cannot
     * see each other.
     */
    public static final String HEX_DIGITS = "0123456789abcdef";

    /**
     * Not to be rewritten as {@code (char) (codePoint - '0') <= 9}. The cast truncates to 16
     * bits, so U+10030 through U+10039 wrap into the digit range, and this takes code points
     * rather than chars precisely because astral characters reach it. The range test is also
     * already what the unsigned trick compiles to.
     */
    public static boolean isDigit(int codePoint) {
        return codePoint >= '0' && codePoint <= '9';
    }

    public static boolean isHexDigit(int codePoint) {
        return isDigit(codePoint) || (codePoint >= 'a' && codePoint <= 'f') || (codePoint >= 'A' && codePoint <= 'F');
    }

    static int hexValue(int codePoint) {
        if (isDigit(codePoint)) {
            return codePoint - '0';
        }

        return (codePoint | 0x20) - 'a' + 10;
    }

    static boolean isLetter(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z') || (codePoint >= 'A' && codePoint <= 'Z');
    }

    /**
     * The explicit list from §4.2.
     *
     * <p>Note this is narrower than "anything above U+007F",
     * an earlier draft said that, and a tokenizer written against the earlier wording
     * accepts idents the current spec rejects.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#tokenizer-definitions">CSS Syntax Level 3
     *      §4.2</a>
     */
    static boolean isNonAsciiIdent(int codePoint) {
        return codePoint == 0x00B7
               || (codePoint >= 0x00C0 && codePoint <= 0x00D6)
               || (codePoint >= 0x00D8 && codePoint <= 0x00F6)
               || (codePoint >= 0x00F8 && codePoint <= 0x037D)
               || (codePoint >= 0x037F && codePoint <= 0x1FFF)
               || codePoint == 0x200C
               || codePoint == 0x200D
               || codePoint == 0x203F
               || codePoint == 0x2040
               || (codePoint >= 0x2070 && codePoint <= 0x218F)
               || (codePoint >= 0x2C00 && codePoint <= 0x2FEF)
               || (codePoint >= 0x3001 && codePoint <= 0xD7FF)
               || (codePoint >= 0xF900 && codePoint <= 0xFDCF)
               || (codePoint >= 0xFDF0 && codePoint <= 0xFFFD)
               || codePoint >= 0x10000;
    }

    /**
     * ASCII name-start code points, one bit each: {@code 0..63} here, {@code 64..127} below.
     *
     * <p>Built from {@link #isLetter} rather than written out as hex. A hand-written constant is
     * a second statement of the spec that can drift from the first silently, and nothing about the
     * number {@code 0x7fffffe} says which code points it claims. Deriving it at class-init leaves
     * one definition the table cannot disagree with, and {@code CodePointsTest} checks every code
     * point from {@code EOF} to {@link #MAX_CODE_POINT} against the predicates.
     */
    private static final long NAME_START_LOW;

    private static final long NAME_START_HIGH;

    /**
     * The same for name code points, which add the digits and {@code -}.
     */
    private static final long NAME_LOW;

    private static final long NAME_HIGH;

    static {
        long startLow = 0;
        long startHigh = 0;
        long low = 0;
        long high = 0;

        for (int codePoint = 0; codePoint < 128; codePoint++) {
            boolean start = isLetter(codePoint) || codePoint == '_';
            if (start) {
                if (codePoint < 64) {
                    startLow |= 1L << codePoint;
                }
                else {
                    startHigh |= 1L << codePoint;
                }
            }

            boolean name = start || isDigit(codePoint) || codePoint == '-';
            if (name) {
                if (codePoint < 64) {
                    low |= 1L << codePoint;
                }
                else {
                    high |= 1L << codePoint;
                }
            }
        }

        NAME_START_LOW = startLow;
        NAME_START_HIGH = startHigh;
        NAME_LOW = low;
        NAME_HIGH = high;
    }

    /**
     * Whether this code point may start an identifier.
     *
     * <p>A bit test for ASCII, and the {@link #isNonAsciiIdent} ranges above it. Written as a
     * chain of comparisons, a code point that is <em>not</em> a letter has to fail all fifteen
     * non-ASCII ranges before anything cheap is tried, and real CSS is full of those: 25% of the
     * identifier characters in the LARGE corpus entry are digits or hyphens, each costing about
     * twenty comparisons.
     *
     * <p>{@code 1L << codePoint} and {@code >>> codePoint} use only the low six bits of the shift
     * distance, so the {@code 64..127} half needs no subtraction. EOF is negative and reaches
     * {@link #isNonAsciiIdent}, whose every range is positive, so it answers false there rather
     * than indexing anything.
     */
    static boolean isIdentStart(int codePoint) {
        if (codePoint >= 0 && codePoint < 128) {
            long mask = codePoint < 64 ? NAME_START_LOW : NAME_START_HIGH;
            return (mask >>> codePoint & 1) != 0;
        }

        return isNonAsciiIdent(codePoint);
    }

    /**
     * Whether this code point may appear in an identifier. See {@link #isIdentStart}.
     */
    public static boolean isIdent(int codePoint) {
        if (codePoint >= 0 && codePoint < 128) {
            long mask = codePoint < 64 ? NAME_LOW : NAME_HIGH;
            return (mask >>> codePoint & 1) != 0;
        }

        return isNonAsciiIdent(codePoint);
    }

    /**
     * Whitespace after preprocessing, where CR and FF have already become LF, so this is
     * narrower than the raw-input definition in §3.3.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-preprocessing">CSS Syntax Level 3 §3.3</a>
     */
    public static boolean isWhitespace(int codePoint) {
        return codePoint == '\n' || codePoint == '\t' || codePoint == ' ';
    }

    static boolean isNonPrintable(int codePoint) {
        return (codePoint >= 0x0000 && codePoint <= 0x0008)
               || codePoint == 0x000B
               || (codePoint >= 0x000E && codePoint <= 0x001F)
               || codePoint == 0x007F;
    }

    static boolean isSurrogate(int codePoint) {
        return codePoint >= 0xD800 && codePoint <= 0xDFFF;
    }

    private CodePoints() {
        // utility class
    }
}
