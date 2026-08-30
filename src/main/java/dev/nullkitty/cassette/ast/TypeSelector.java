package dev.nullkitty.cassette.ast;

/**
 * An element name, or the universal selector: {@code div}, {@code *}, {@code svg|circle}.
 *
 * <p>The universal selector lives here rather than in a type of its own because that is what
 * the grammar says, {@code <type-selector> = <wq-name> | <ns-prefix>? '*'}, and because
 * both occupy the same position in a compound selector. They differ only in specificity,
 * which {@link #isUniversal()} answers.
 *
 * <p>Namespace prefixes have three states, and {@code null} is not the same as {@code ""}:
 *
 * <ul>
 *   <li>{@code null}, no prefix written ({@code div}); matches the default namespace</li>
 *   <li>{@code "*"}, any namespace ({@code *|div})</li>
 *   <li>{@code ""}, no namespace ({@code |div})</li>
 * </ul>
 *
 * @param namespace  the namespace prefix, or {@code null} if none was written
 * @param name       the element name, or {@code *} for the universal selector
 * @param packedSpan the packed region of source this selector was parsed from
 */
public record TypeSelector(String namespace, //
                           String name,
                           long packedSpan)
    implements
        SimpleSelector {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public TypeSelector(String namespace, //
                        String name,
                        SourceSpan span) {
        this(namespace, name, span.packed());
    }

    /**
     * Whether this is the universal selector, which contributes nothing to specificity.
     *
     * @return whether the name is {@code *}
     */
    public boolean isUniversal() {
        return "*".equals(this.name);
    }
}
