package dev.nullkitty.cassette.ast;

import java.util.List;

/**
 * Comma-separated alternatives: {@code .card, .panel}.
 *
 * <p>A style rule's prelude is always one of these, even when the author wrote a single
 * selector, a list of one. Keeping the shape uniform means flattening never has to ask
 * whether it is looking at a list or a bare selector.
 *
 * @param selectors  the alternatives, in source order, never empty for a valid rule
 * @param packedSpan the packed region of source this list was parsed from
 */
public record SelectorList(List<ComplexSelector> selectors, //
                           long packedSpan)
    implements
        Selector {

    /**
     * Copies {@code selectors} so the record is genuinely immutable.
     *
     * @throws NullPointerException if any argument or element is {@code null}
     */
    public SelectorList {
        selectors = List.copyOf(selectors);
    }

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public SelectorList(List<ComplexSelector> selectors, //
                        SourceSpan span) {
        this(selectors, span.packed());
    }

    /**
     * Wraps a single selector as a one-element list.
     *
     * @param selector the only alternative
     * @return a list holding just it, spanning the same source
     */
    public static SelectorList of(ComplexSelector selector) {
        return new SelectorList(List.of(selector), selector.span());
    }

    /**
     * Whether this list holds more than one alternative, and so cannot be substituted into a
     * compound selector without {@code :is()}.
     *
     * @return whether there is more than one selector
     */
    public boolean isMultiple() {
        return this.selectors.size() > 1;
    }
}
