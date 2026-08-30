package dev.nullkitty.cassette.ast;

/**
 * The case-sensitivity modifier on an {@link AttributeSelector}:
 * the {@code i} or {@code s} in {@code [type="TEXT" i]}.
 *
 * <p>{@link #UNSPECIFIED} is not the same as {@link #SENSITIVE}. Which one an omitted
 * modifier means depends on the document language, HTML matches some attributes
 * case-insensitively by default, and this parser has no document.
 */
public enum AttributeCase {

    /**
     * No modifier written; the document language decides.
     */
    UNSPECIFIED(""),

    /**
     * {@code i}: match ASCII case-insensitively.
     */
    INSENSITIVE("i"),

    /**
     * {@code s}: match case-sensitively, whatever the document language would do.
     */
    SENSITIVE("s");

    private final String text;

    AttributeCase(String text) {
        this.text = text;
    }

    /**
     * How this modifier is written.
     *
     * @return the modifier's source text, empty for {@link #UNSPECIFIED}
     */
    public String text() {
        return this.text;
    }
}
