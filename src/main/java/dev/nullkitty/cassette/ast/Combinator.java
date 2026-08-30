package dev.nullkitty.cassette.ast;

/**
 * How one compound selector relates to the one before it.
 *
 * <p>{@link #NONE} is not a combinator in the grammar, it marks the first step of a
 * complex selector, which has nothing to its left to relate to. Keeping it in the enum
 * rather than making the first step's combinator {@code null} means a {@code switch} over
 * this type is exhaustive without a null check in front of it.
 */
public enum Combinator {

    /**
     * No combinator: the first compound selector in a complex selector.
     */
    NONE(""),

    /**
     * Whitespace, {@code a b}: a descendant at any depth.
     */
    DESCENDANT(" "),

    /**
     * {@code a > b}: a direct child.
     */
    CHILD(">"),

    /**
     * {@code a + b}: the immediately following sibling.
     */
    NEXT_SIBLING("+"),

    /**
     * {@code a ~ b}: any following sibling.
     */
    SUBSEQUENT_SIBLING("~"),

    /**
     * {@code a || b}: a cell in a column.
     *
     * <p>Part of Selectors Level 5 and parsed here for completeness; no engine ships it.
     *
     * @see <a href="https://drafts.csswg.org/selectors-5/#the-column-combinator">Selectors Level 5 §8.1
     *      Column combinator (||)</a>
     */
    COLUMN("||");

    private final String text;

    Combinator(String text) {
        this.text = text;
    }

    /**
     * How this combinator is written.
     *
     * @return the combinator's source text, a single space for {@link #DESCENDANT} and empty
     *         for {@link #NONE}
     */
    public String text() {
        return this.text;
    }
}
