package dev.nullkitty.cassette.ast;

/**
 * A single condition on an element: a tag name, a class, an id, an attribute test, a
 * pseudo-class, a pseudo-element, or {@code &}.
 *
 * <p>The smallest unit the selector grammar has. Everything above this level is composition.
 */
public sealed interface SimpleSelector extends Selector
    permits //
        TypeSelector,
        ClassSelector,
        IdSelector,
        AttributeSelector,
        PseudoClassSelector,
        PseudoElementSelector,
        NestingSelector {

    /**
     * Whether this selector is, or contains, a {@link NestingSelector}.
     *
     * <p>Nesting selectors hide inside functional pseudo-classes, {@code :is(& .a)} is
     * legal, so this is a recursive question and not an {@code instanceof} check.
     *
     * @return whether flattening has to rewrite this selector
     */
    default boolean containsNestingSelector() {
        return this instanceof NestingSelector;
    }
}
