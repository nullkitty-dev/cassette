package dev.nullkitty.cassette.sourcemap;

import java.io.IOException;
import java.util.List;

import dev.nullkitty.cassette.lexer.CodePoints;

/**
 * Enough JSON to write a source map, which is one flat object of strings and arrays.
 *
 * <p>Not a general JSON writer and not a parser. A map holds no numbers a caller supplies, no
 * nesting beyond one array level and no booleans, so what is here is a string escaper and two
 * list forms. Zero dependencies means writing it; the narrowness is what keeps that cheap.
 *
 * <p>An instance rather than static methods, because of the comma. Every member of a map is
 * optional, so whether one needs a separator in front of it depends on whether anything was
 * written before. Reading that off the buffer by comparing the last character against the opening
 * brace would work only for a {@code StringBuilder}; holding it in a field is what lets
 * {@link SourceMap#writeJson} write to any {@code Appendable}.
 */
final class Json {

    /**
     * U+2028, a line terminator in JavaScript but not in JSON.
     */
    private static final char LINE_SEPARATOR = 0x2028;

    /**
     * U+2029, the same.
     */
    private static final char PARAGRAPH_SEPARATOR = 0x2029;

    private static final String HEX = CodePoints.HEX_DIGITS;

    private final Appendable out;

    /**
     * Whether the next member is the first, and so needs no comma in front of it.
     */
    private boolean first = true;

    Json(Appendable out) {
        this.out = out;
    }

    /**
     * Opens the object.
     */
    Json open() throws IOException {
        this.out.append('{');
        return this;
    }

    /**
     * Closes it.
     */
    void close() throws IOException {
        this.out.append('}');
    }

    /**
     * Appends {@code "name":"value"}, or nothing at all when {@code value} is null.
     */
    Json member(String name, //
                CharSequence value) throws IOException {
        if (value == null) {
            return this;
        }

        separate();
        string(name);

        this.out.append(':');

        string(value);

        return this;
    }

    /**
     * Appends {@code "name":[...]}, or nothing at all when {@code values} is null.
     */
    Json member(String name, //
                List<String> values) throws IOException {
        if (values == null) {
            return this;
        }

        separate();
        string(name);

        this.out.append(":[");

        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                this.out.append(',');
            }

            string(values.get(index));
        }

        this.out.append(']');

        return this;
    }

    /**
     * Appends {@code "name":<number>}.
     */
    Json member(String name, //
                int value) throws IOException {

        separate();
        string(name);

        this.out.append(':').append(Integer.toString(value));

        return this;
    }

    private void separate() throws IOException {
        if (!this.first) {
            this.out.append(',');
        }

        this.first = false;
    }

    /**
     * Escapes and quotes one string.
     *
     * <p>{@code "}, {@code \} and U+0000–U+001F are what the grammar requires. U+2028 and U+2029
     * are not, and are escaped anyway, because a map is routinely inlined into a {@code data:} URI
     * in a JavaScript context, where both are line terminators and would end the statement.
     *
     * <p>No lone-surrogate rule is needed, which follows from the decode contract rather than from
     * an assumption about input. §3.3 replaces every unpaired surrogate with U+FFFD during
     * preprocessing, so text that reached a {@code SourceIndex} or a {@code SourceResolver} cannot
     * contain one.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-preprocessing">CSS Syntax Level 3 §3.3</a>
     */
    private void string(CharSequence value) throws IOException {
        this.out.append('"');

        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);

            switch (c) {
                case '"' -> this.out.append("\\\"");
                case '\\' -> this.out.append("\\\\");
                case '\b' -> this.out.append("\\b");
                case '\f' -> this.out.append("\\f");
                case '\n' -> this.out.append("\\n");
                case '\r' -> this.out.append("\\r");
                case '\t' -> this.out.append("\\t");

                // Compared as numbers and not as character literals: U+2028 is a line
                // terminator to the Java lexer too, so writing one inside a char literal
                // does not compile whichever way it is spelled.
                default -> {
                    if (c < 0x20 || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR) {
                        escape(c);
                    }
                    else {
                        this.out.append(c);
                    }
                }
            }
        }

        this.out.append('"');
    }

    /**
     * A four-digit {@code \\u} escape, which is what the control characters need.
     */
    private void escape(char c) throws IOException {
        this.out.append("\\u") //
                .append(HEX.charAt((c >> 12) & 0xf)) //
                .append(HEX.charAt((c >> 8) & 0xf)) //
                .append(HEX.charAt((c >> 4) & 0xf)) //
                .append(HEX.charAt(c & 0xf));
    }
}
