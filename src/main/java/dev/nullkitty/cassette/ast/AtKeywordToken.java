package dev.nullkitty.cassette.ast;

/**
 * An at-keyword appearing somewhere it does not start a rule, as {@code @media} does inside
 * a {@code @supports} prelude.
 *
 * <p>An at-keyword that <em>does</em> start a rule becomes an {@link AtRule} or a
 * {@link ConditionalGroupRule}, not this.
 *
 * @param name       the keyword without its {@code @}, with escapes resolved
 * @param packedSpan the packed region of source this token was parsed from, {@code @} included
 */
public record AtKeywordToken(String name, long packedSpan) implements PreservedToken {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public AtKeywordToken(String name, SourceSpan span) {
        this(name, span.packed());
    }
}
