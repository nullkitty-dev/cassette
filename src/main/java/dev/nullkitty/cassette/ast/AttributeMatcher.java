package dev.nullkitty.cassette.ast;

/**
 * How an {@link AttributeSelector} compares an attribute's value.
 */
public enum AttributeMatcher {

    /**
     * {@code [href]}: the attribute exists, whatever its value.
     */
    PRESENT(""),

    /**
     * {@code [type="text"]}: exactly equal.
     */
    EXACT("="),

    /**
     * {@code [class~="a"]}: one of the whitespace-separated words.
     */
    INCLUDES("~="),

    /**
     * {@code [lang|="en"]}: equal, or equal up to a following {@code -}.
     */
    DASH("|="),

    /**
     * {@code [href^="https"]}: starts with.
     */
    PREFIX("^="),

    /**
     * {@code [src$=".png"]}: ends with.
     */
    SUFFIX("$="),

    /**
     * {@code [title*="x"]}: contains.
     */
    SUBSTRING("*=");

    private final String text;

    AttributeMatcher(String text) {
        this.text = text;
    }

    /**
     * How this matcher is written.
     *
     * @return the operator's source text, empty for {@link #PRESENT}
     */
    public String text() {
        return this.text;
    }
}
