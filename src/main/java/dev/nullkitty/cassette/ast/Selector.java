package dev.nullkitty.cassette.ast;

/**
 * A selector, at any level of the Selectors Level 4 grammar.
 *
 * <p>This is a second grammar living alongside CSS Syntax's rules and declarations, not a
 * side effect of them. The reason it is structural rather than an opaque token span is
 * flattening: expanding {@code &} correctly means finding it wherever it sits in a compound
 * selector and wrapping the right part of the parent selector list in {@code :is()}, which
 * needs to know what a compound selector <em>is</em>.
 *
 * <p>The four levels nest strictly:
 *
 * <pre>
 * SelectorList       .card > .title, .panel &gt; .title
 * ComplexSelector    .card &gt; .title
 * CompoundSelector   .title
 * SimpleSelector     .title
 * </pre>
 *
 * @see <a href="https://www.w3.org/TR/selectors-4/#grammar">Selectors Level 4 §16 Grammar</a>
 */
public sealed interface Selector extends Node
    permits //
        SelectorList,
        ComplexSelector,
        CompoundSelector,
        SimpleSelector {

    /**
     * This selector's specificity.
     *
     * @return the (id, class, type) triple this selector contributes to the cascade
     * @see Specificity
     */
    default Specificity specificity() {
        return Specificity.of(this);
    }
}
