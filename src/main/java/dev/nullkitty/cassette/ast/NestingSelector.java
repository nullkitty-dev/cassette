package dev.nullkitty.cassette.ast;

/**
 * The nesting selector, {@code &}: a stand-in for the enclosing rule's selector list.
 *
 * <p>It occupies a compound-selector position like a pseudo-class does, and may appear more
 * than once, anywhere in the selector, including inside a functional pseudo-class's
 * arguments.
 *
 * <p>Specificity is the one thing this node cannot answer on its own. {@code &} takes the
 * specificity of the most specific selector in the parent list, which a node with no parent
 * pointer does not know; {@link Specificity#of(Selector)} therefore counts it as zero.
 * Compute specificity after flattening has substituted the parent in, or supply the parent
 * yourself.
 *
 * @param packedSpan the packed region of source this selector was parsed from
 */
public record NestingSelector(long packedSpan) implements SimpleSelector {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public NestingSelector(SourceSpan span) {
        this(span.packed());
    }
}
