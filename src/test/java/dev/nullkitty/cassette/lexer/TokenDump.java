package dev.nullkitty.cassette.lexer;

/**
 * Renders a token stream as one line per token, for golden fixtures.
 *
 * <pre>
 * IDENT        0..1   |a|
 * LEFT_CURLY   1..2   |{|
 * HASH         2..6   |#foo| value=|foo| id
 * DIMENSION    6..10  |10px| value=|px| number=10 integer
 * </pre>
 *
 * <p>Only the fields that carry information for a given token appear, so a diff points at
 * what actually changed rather than at a wall of defaults. Raw text comes first and is
 * always present; {@code value=} is emitted only where it differs from the raw text.
 */
public final class TokenDump {

    private TokenDump() {
        // static-only
    }

    /**
     * Tokenizes {@code css} as already-decoded text and dumps the result.
     */
    public static String of(String css) {
        return of(SourceText.of(css));
    }

    /**
     * Tokenizes raw bytes, exercising charset detection, and dumps the result.
     */
    public static String of(byte[] css) {
        return of(SourceText.decode(css));
    }

    /**
     * Dumps every token in {@code source}, excluding the final EOF.
     */
    public static String of(SourceText source) {
        Tokenizer tokenizer = new Tokenizer(source);
        StringBuilder out = new StringBuilder();
        while (tokenizer.next() != TokenType.EOF) {
            appendToken(out, tokenizer);
            out.append('\n');
        }

        return out.toString();
    }

    private static void appendToken(StringBuilder out, Tokenizer token) {
        TokenType type = token.type();
        pad(out, type.name(), 12);
        pad(out, token.start() + ".." + token.end(), 11);
        out.append('|').append(escape(token.raw())).append('|');

        String value = token.value();
        if (!value.equals(token.raw()) && type != TokenType.BAD_URL) {
            out.append(" value=|").append(escape(value)).append('|');
        }

        if (type.isNumeric()) {
            out.append(" number=").append(formatNumber(token.numericValue()));
        }

        if (token.isIdHash()) {
            out.append(" id");
        }

        if (type.isNumeric() && token.isInteger()) {
            out.append(" integer");
        }

        if (token.hasSign()) {
            out.append(" signed");
        }

        if (token.hasExponent()) {
            out.append(" exponent");
        }

        if (token.hasEscape()) {
            out.append(" escaped");
        }

        if (isDelimited(type) && !token.isTerminated()) {
            out.append(" unterminated");
        }
    }

    private static boolean isDelimited(TokenType type) {
        return type == TokenType.STRING || type == TokenType.URL || type == TokenType.COMMENT;
    }

    /**
     * Renders whole values without a trailing {@code .0}, so {@code 10px} dumps as {@code 10}.
     */
    private static String formatNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }

        return Double.toString(value);
    }

    private static void pad(StringBuilder out, String text, int width) {
        out.append(text);

        for (int i = text.length(); i < width; i++) {
            out.append(' ');
        }
    }

    /**
     * Keeps every token on its own line, and makes trailing whitespace visible in a diff.
     */
    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            switch (c) {
                case '\n' -> escaped.append("\\n");
                case '\t' -> escaped.append("\\t");
                case '\\' -> escaped.append("\\\\");
                case '|' -> escaped.append("\\|");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        escaped.append(String.format("\\u%04X", (int) c));
                    }
                    else {
                        escaped.append(c);
                    }
                }
            }
        }

        return escaped.toString();
    }
}
