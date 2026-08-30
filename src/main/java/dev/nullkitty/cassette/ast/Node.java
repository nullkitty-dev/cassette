package dev.nullkitty.cassette.ast;

/**
 * Anything in the tree that came from somewhere in the source.
 *
 * <p>The hierarchy is sealed so exhaustive {@code switch} over it is checked by the
 * compiler: a transform that forgets a case fails to build rather than silently dropping
 * nodes. Every implementation is an immutable record.
 *
 * <p>Two grammars meet here. {@link Rule}, {@link Declaration} and {@link ComponentValue}
 * come from CSS Syntax Module Level 3; {@link Selector} and its subtypes come from
 * Selectors Level 4, and appear only inside a {@link StyleRule}'s prelude.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/">CSS Syntax Module Level 3</a>
 * @see <a href="https://www.w3.org/TR/selectors-4/">Selectors Level 4</a>
 */
public sealed interface Node
    permits //
        Stylesheet,
        Rule,
        Declaration,
        ComponentValue,
        Selector {

    /**
     * Where this node came from, packed.
     *
     * <p>This is the record component every node declares; {@link #span()} is the
     * readable view of it. See {@link SourceSpan} for why the tree stores the packed form.
     *
     * @return the region of source text this node was parsed from, in the form
     *         {@link SourceSpan#pack} produces, or {@link SourceSpan#NONE_PACKED} if it was
     *         synthesized
     */
    long packedSpan();

    /**
     * Where this node came from.
     *
     * <p>Builds a new {@link SourceSpan} on every call. Reading offsets across a whole tree
     * is cheaper through {@link SourceSpan#startOf} and {@link SourceSpan#lengthOf} over
     * {@link #packedSpan()}.
     *
     * @return the region of source text this node was parsed from, or
     *         {@link SourceSpan#NONE} if it was synthesized
     */
    default SourceSpan span() {
        return SourceSpan.unpack(packedSpan());
    }
}
