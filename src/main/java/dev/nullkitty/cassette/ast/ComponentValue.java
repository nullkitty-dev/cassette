package dev.nullkitty.cassette.ast;

/**
 * CSS Syntax Level 3's component value: a preserved token, a function, or a simple block.
 *
 * <p>This is the currency of everything the parser does not interpret, declaration values,
 * at-rule preludes, opaque functional pseudo-class arguments. The nesting is real: a
 * {@link FunctionValue}'s arguments and a {@link SimpleBlock}'s contents are themselves
 * component values, which is what makes a later {@code calc()} folding pass possible
 * without redesigning the tree.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#component-value">CSS Syntax Level 3 §5 Parsing,
 *      component values</a>
 */
public sealed interface ComponentValue extends Node permits PreservedToken, FunctionValue, SimpleBlock {

    /**
     * Whether this value carries no meaning of its own: whitespace or a comment.
     *
     * <p>Everything except a formatter wants to skip these.
     *
     * @return whether this is trivia
     */
    default boolean isTrivia() {
        return this instanceof WhitespaceToken || this instanceof Comment;
    }
}
