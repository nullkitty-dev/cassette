package dev.nullkitty.cassette.ast;

import java.util.List;

import dev.nullkitty.cassette.text.Ascii;

/**
 * A function and its arguments: {@code rgb(0 0 0)}, {@code var(--x, 1px)},
 * {@code calc(100% - 2rem)}.
 *
 * <p>Arguments are a flat component-value list, commas included as {@link Punctuation}, not
 * a list-of-lists split on commas. That is what the spec's grammar produces, and splitting
 * would be wrong anyway, {@code calc()} has no arguments in the comma sense, and
 * {@code var(--x, a, b)} has a fallback containing a comma.
 *
 * <p>Named {@code FunctionValue} rather than the spec's bare {@code Function} to stay out of
 * the way of {@link java.util.function.Function}, which any consumer of this package is
 * likely to have imported.
 *
 * @param name       the function name without its {@code (}, with escapes resolved and case preserved
 * @param arguments  everything between the parentheses, as component values
 * @param packedSpan the packed region of source this function was parsed from, both parentheses included
 */
public record FunctionValue(String name, //
                            List<ComponentValue> arguments,
                            long packedSpan)
    implements
        ComponentValue {

    /**
     * Copies {@code arguments} so the record is genuinely immutable.
     *
     * @throws NullPointerException if any argument or list element is {@code null}
     */
    public FunctionValue {
        arguments = List.copyOf(arguments);
    }

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public FunctionValue(String name, //
                         List<ComponentValue> arguments,
                         SourceSpan span) {
        this(name, arguments, span.packed());
    }

    /**
     * Compares this function's name to a literal, the ASCII case-insensitive way CSS matches
     * function names.
     *
     * @param expected the lowercase name to compare against
     * @return whether the names match
     */
    public boolean nameIs(String expected) {
        return Ascii.equalsIgnoreCase(this.name, expected);
    }
}
