package dev.nullkitty.cassette.serializer;

import dev.nullkitty.cassette.lexer.CodePoints;

/**
 * Canonical re-escaping of identifiers, strings and URLs, per CSSOM §2.1 "Common Serializing
 * Idioms".
 *
 * <p>The AST holds decoded text, so an identifier written {@code caf\e9} arrives as {@code café}.
 * This is not reproducing an author's escape style, which was never recorded. It decides from
 * scratch the minimal escaping that reads back as the same text.
 *
 * <p>A hex escape always ends with a space, because {@code \e9} followed by anything
 * hex-digit-like would otherwise swallow it. That includes the separator: {@code .caf\e9 .x}
 * without the terminating space is {@code .café.x}, a different selector.
 *
 * @see <a href="https://www.w3.org/TR/cssom-1/#common-serializing-idioms">CSSOM §2.1</a>
 */
final class Escaping {

    /**
     * Writes {@code value} as an identifier: a class name, a property, a function name.
     *
     * @param out      where to write
     * @param value    the decoded text
     * @param encoding whether non-ASCII characters are escaped
     */
    static void ident(StringBuilder out, String value, IdentifierEncoding encoding) {
        if (value.isEmpty()) {
            return;
        }

        if ("-".equals(value)) {
            // A lone '-' is an ident only when escaped; unescaped it is a delimiter.
            out.append("\\-");
            return;
        }

        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            identCodePoint(out, codePoint, index, value, encoding);
            index += Character.charCount(codePoint);
        }
    }

    private static void identCodePoint(StringBuilder out,
                                       int codePoint,
                                       int index,
                                       String value,
                                       IdentifierEncoding encoding) {
        if (codePoint == 0) {
            // A NULL never survives preprocessing, but a caller may have synthesized one.
            out.append('�');
            return;
        }

        if (codePoint <= 0x1F || codePoint == 0x7F) {
            hex(out, codePoint);
            return;
        }

        boolean leadingDigit = index == 0 && CodePoints.isDigit(codePoint);
        boolean secondDigitAfterDash = index == 1 && CodePoints.isDigit(codePoint) && value.charAt(0) == '-';
        if (leadingDigit || secondDigitAfterDash) {
            // '3px' as a class name has to escape its digit or it lexes as a dimension.
            hex(out, codePoint);
            return;
        }

        if (codePoint >= 0x80) {
            if (encoding == IdentifierEncoding.ASCII) {
                hex(out, codePoint);
            }
            else {
                out.appendCodePoint(codePoint);
            }

            return;
        }

        if (isNameChar(codePoint)) {
            out.append((char) codePoint);
            return;
        }

        out.append('\\').append((char) codePoint);
    }

    /**
     * Writes {@code value} as a hash token's name: {@code #336699} as much as {@code #main}.
     *
     * <p>Unlike an identifier, this may open with a digit, which is what a hash token that is
     * not an id consists of. Escaping the {@code 3} of {@code #336699} would make it
     * unreadable.
     *
     * @param out      where to write
     * @param value    the decoded text, without the {@code #}
     * @param encoding whether non-ASCII characters are escaped
     */
    static void name(StringBuilder out, //
                     String value,
                     IdentifierEncoding encoding) {
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);

            if (codePoint == 0) {
                out.append('�');
            }
            else if (codePoint <= 0x1F || codePoint == 0x7F) {
                hex(out, codePoint);
            }
            else if (codePoint >= 0x80) {
                if (encoding == IdentifierEncoding.ASCII) {
                    hex(out, codePoint);
                }
                else {
                    out.appendCodePoint(codePoint);
                }
            }
            else if (isNameChar(codePoint)) {
                out.append((char) codePoint);
            }
            else {
                out.append('\\').append((char) codePoint);
            }
        }
    }

    /**
     * Writes {@code unit} as a dimension's unit, which is an identifier with one extra rule.
     *
     * <p>A unit spelled {@code e5}, which only an escape can produce, would make {@code 1} and
     * {@code e5} concatenate into the number {@code 1e5}. Escaping the {@code e} keeps the
     * dimension a dimension.
     *
     * @param out      where to write
     * @param unit     the decoded unit
     * @param encoding whether non-ASCII characters are escaped
     */
    static void unit(StringBuilder out, //
                     String unit,
                     IdentifierEncoding encoding) {
        if (looksLikeExponent(unit)) {
            hex(out, unit.charAt(0));

            // The rest by name rules, not ident rules: its leading digit is no longer the
            // identifier's first character and does not need escaping too.
            name(out, unit.substring(1), encoding);
            return;
        }

        ident(out, unit, encoding);
    }

    private static boolean looksLikeExponent(String unit) {
        if (unit.isEmpty() || (unit.charAt(0) != 'e' && unit.charAt(0) != 'E')) {
            return false;
        }

        int next = unit.length() > 1 && (unit.charAt(1) == '+' || unit.charAt(1) == '-') ? 2 : 1;
        return next < unit.length() && CodePoints.isDigit(unit.charAt(next));
    }

    /**
     * Writes {@code value} as a double-quoted string.
     *
     * <p>Always double quotes: which quote character the author used is not recorded, and
     * picking one is a serializer decision.
     *
     * @param out      where to write
     * @param value    the decoded text
     * @param encoding whether non-ASCII characters are escaped
     */
    static void string(StringBuilder out, //
                       String value,
                       IdentifierEncoding encoding) {
        out.append('"');

        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);

            switch (codePoint) {
                case 0 -> out.append('�');
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (codePoint <= 0x1F
                        || codePoint == 0x7F
                        || (codePoint >= 0x80 && encoding == IdentifierEncoding.ASCII)) {
                        hex(out, codePoint);
                    }
                    else {
                        out.appendCodePoint(codePoint);
                    }
                }
            }
        }

        out.append('"');
    }

    /**
     * Writes {@code value} as a {@code url()}, unquoted where the grammar allows it.
     *
     * <p>An unquoted URL is the shorter form and the one authors write, but its grammar is
     * narrow, no whitespace, quotes, parentheses or control characters, so anything else
     * falls back to {@code url("...")}, which is a function taking an ordinary string.
     *
     * @param out      where to write
     * @param value    the decoded URL
     * @param encoding whether non-ASCII characters are escaped
     */
    static void url(StringBuilder out, String value, IdentifierEncoding encoding) {
        out.append("url(");

        if (isBareUrl(value, encoding)) {
            out.append(value);
        }
        else {
            string(out, value, encoding);
        }

        out.append(')');
    }

    private static boolean isBareUrl(String value, IdentifierEncoding encoding) {
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);

            boolean printable = c > 0x20 && c != 0x7F;
            boolean allowed = c != '"' && c != '\'' && c != '(' && c != ')' && c != '\\';
            if (!printable || !allowed) {
                return false;
            }

            if (c >= 0x80 && encoding == IdentifierEncoding.ASCII) {
                return false;
            }
        }

        return true;
    }

    /**
     * {@code \} + lowercase hex + the space that terminates it.
     */
    private static void hex(StringBuilder out, int codePoint) {
        out.append('\\');

        int shift = 20;
        while (shift > 0 && (codePoint >>> shift) == 0) {
            shift -= 4;
        }

        while (shift >= 0) {
            out.append(CodePoints.HEX_DIGITS.charAt((codePoint >>> shift) & 0xF));
            shift -= 4;
        }

        out.append(' ');
    }

    /**
     * The ASCII half of §4.2's name code points, answered from {@link CodePoints}' bit
     * table rather than a chain of range tests.
     *
     * <p>A chain of range tests puts {@code -}, {@code _} and the digits before the lowercase
     * letters, which are 75 to 83% of the name characters in real CSS. Both callers dispatch
     * {@code >= 0x80} above this, so the ASCII gate is what keeps the contract narrower than
     * {@code isIdent}, whose non-ASCII half admits characters an identifier may hold but this
     * predicate must decline.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#tokenizer-definitions">CSS Syntax Level 3
     *      §4.2</a>
     */
    private static boolean isNameChar(int codePoint) {
        return codePoint < 0x80 && CodePoints.isIdent(codePoint);
    }

    private Escaping() {
        // utility class
    }

}
