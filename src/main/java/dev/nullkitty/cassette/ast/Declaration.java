package dev.nullkitty.cassette.ast;

import java.util.List;

/**
 * A property and its value: {@code color: red} or {@code --brand: 1px solid}.
 *
 * <p>{@code value} is a list rather than a single {@link ComponentValue} because a
 * declaration value genuinely is a token sequence, {@code 1px solid red} is three values
 * and two separators, not one thing.
 * The list is stored with leading and trailing whitespace already trimmed, and with
 * {@code !important} removed into {@link #important()}, but is otherwise exactly what
 * the author wrote.
 *
 * <p>Nothing here is evaluated. A {@code var()} or {@code calc()} in the value is a
 * {@link FunctionValue} holding more component values, and a custom property's value is
 * arbitrary token soup by definition; the spec says so, because resolving either needs
 * computed-value context a standalone parser does not have.
 *
 * @param property   the property name, with escapes resolved and ASCII case preserved
 * @param value      the value's component values, whitespace-trimmed at both ends
 * @param important  whether the value carried a trailing {@code !important}
 * @param packedSpan the packed region of source this declaration was parsed from
 */
public record Declaration(String property, //
                          List<ComponentValue> value,
                          boolean important,
                          long packedSpan)
    implements
        Node {

    /**
     * Copies {@code value} so the record is genuinely immutable.
     *
     * @throws NullPointerException if any argument or value element is {@code null}
     */
    public Declaration {
        value = List.copyOf(value);
    }

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public Declaration(String property, //
                       List<ComponentValue> value,
                       boolean important,
                       SourceSpan span) {
        this(property, value, important, span.packed());
    }

    /**
     * Whether this is a custom property, whose value the spec treats as opaque.
     *
     * @return whether the property name starts with {@code --}
     */
    public boolean isCustomProperty() {
        return this.property.startsWith("--");
    }
}
