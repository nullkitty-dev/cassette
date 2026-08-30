package dev.nullkitty.cassette.lexer;

import dev.nullkitty.cassette.text.Ascii;

/**
 * Resolution of CSS escape sequences against a decoded buffer.
 *
 * <p>Extracted from {@link Tokenizer} so {@link TokenBuffer} can decode a token's value long
 * after the cursor has moved past it. Both work from {@code (buffer, from, to)} triples, so
 * neither needs the other's position state.
 *
 * <p>Case folding is {@link Ascii}'s, not this class's and not {@link String}'s. It sits in
 * {@code text} because {@code ast} needs the same folding and cannot import this package.
 */
final class Escapes {

    private Escapes() {
        // static-only
    }

    /**
     * Resolves the escapes in {@code [from, to)} into their code points.
     *
     * <p>Callers that know the range holds no backslash should skip this and read the range
     * straight off the buffer; it always allocates.
     *
     * @param input the decoded source buffer
     * @param from  index of the first character to decode
     * @param to    index one past the last character to decode
     * @return the decoded text
     */
    static String unescape(char[] input, int from, int to) {
        StringBuilder decoded = new StringBuilder(to - from);
        int at = from;

        while (at < to) {
            char c = input[at];

            if (c != '\\') {
                decoded.append(c);
                at++;
                continue;
            }

            at++;

            if (at >= to) {
                decoded.append(CodePoints.REPLACEMENT);
                break;
            }

            char next = input[at];

            if (next == '\n') {
                // An escaped newline inside a string is a line continuation: it contributes
                // nothing to the value.
                at++;
                continue;
            }

            if (CodePoints.isHexDigit(next)) {
                int value = 0;
                int digits = 0;

                while (at < to && digits < 6 && CodePoints.isHexDigit(input[at])) {
                    value = value * 16 + CodePoints.hexValue(input[at]);
                    at++;
                    digits++;
                }

                if (at < to && CodePoints.isWhitespace(input[at])) {
                    at++;
                }

                if (value == 0 || CodePoints.isSurrogate(value) || value > CodePoints.MAX_CODE_POINT) {
                    decoded.append(CodePoints.REPLACEMENT);
                }
                else {
                    decoded.appendCodePoint(value);
                }

                continue;
            }

            int cp = codePointAt(input, at, to);
            decoded.appendCodePoint(cp);
            at += Character.charCount(cp);
        }

        return decoded.toString();
    }

    /**
     * ASCII case-insensitive comparison of {@code [from, to)} against a literal.
     *
     * <p>Allocates only when the range contains an escape, which is why {@code escaped} is a
     * parameter rather than something this rediscovers by scanning.
     *
     * @param input    the decoded source buffer
     * @param from     index of the first character to compare
     * @param to       index one past the last character to compare
     * @param expected the ASCII literal to compare against
     * @param escaped  whether the range is known to contain an escape sequence
     * @return whether the range equals {@code expected}, ignoring ASCII case
     */
    static boolean equalsIgnoreCase(char[] input, //
                                    int from,
                                    int to,
                                    String expected,
                                    boolean escaped) {
        if (escaped) {
            // Ascii.equalsIgnoreCase and not String's, or escaping an identifier would change
            // what it matches: `\17f` decodes to U+017F, which String.equalsIgnoreCase accepts
            // as an `s`, while the loop below rejects the same character written literally.
            return Ascii.equalsIgnoreCase(unescape(input, from, to), expected);
        }

        if (to - from != expected.length()) {
            return false;
        }

        for (int i = 0; i < expected.length(); i++) {
            char actual = input[from + i];
            char want = expected.charAt(i);
            if (actual != want && Ascii.lower(actual) != Ascii.lower(want)) {
                return false;
            }
        }

        return true;
    }

    private static int codePointAt(char[] input, int at, int limit) {
        char c = input[at];
        if (Character.isHighSurrogate(c) && at + 1 < limit && Character.isLowSurrogate(input[at + 1])) {
            return Character.toCodePoint(c, input[at + 1]);
        }

        return c;
    }
}
