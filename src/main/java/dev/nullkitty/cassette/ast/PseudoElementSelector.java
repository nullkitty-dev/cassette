package dev.nullkitty.cassette.ast;

import java.util.List;
import java.util.Set;

import dev.nullkitty.cassette.text.Ascii;

/**
 * A pseudo-element: {@code ::before}, {@code ::part(label)}, or the legacy {@code :before}.
 *
 * <p>{@code doubleColon} records which spelling the author used, and is the one piece of source
 * style this AST keeps. Four pseudo-elements predate the {@code ::} syntax and are still legal with
 * one colon, and rewriting {@code :before} to {@code ::before} would break the old engines that are
 * the whole reason the serializer has a legacy mode.
 *
 * <p>Arguments stay unparsed. {@code ::part()} takes idents, {@code ::slotted()} takes a
 * compound selector, and neither affects flattening or specificity.
 *
 * @param name        the pseudo-element name without its colons, with escapes resolved
 * @param doubleColon whether it was written {@code ::} rather than the legacy {@code :}
 * @param functional  whether it was written with parentheses, even empty ones
 * @param arguments   the unparsed arguments, empty when there are none
 * @param packedSpan  the packed region of source this selector was parsed from, colons included
 */
public record PseudoElementSelector(String name, //
                                    boolean doubleColon,
                                    boolean functional,
                                    List<ComponentValue> arguments,
                                    long packedSpan)
    implements
        SimpleSelector {

    /**
     * The pseudo-elements that predate {@code ::} and may still be written with one colon.
     */
    private static final Set<String> LEGACY = Set.of("before", //
                                                     "after",
                                                     "first-line",
                                                     "first-letter");

    /**
     * Copies {@code arguments} so the record is genuinely immutable.
     *
     * @throws NullPointerException if {@code name}, {@code arguments}, {@code span} or any
     *         argument element is {@code null}
     */
    public PseudoElementSelector {
        arguments = List.copyOf(arguments);
    }

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public PseudoElementSelector(String name,
                                 boolean doubleColon,
                                 boolean functional,
                                 List<ComponentValue> arguments,
                                 SourceSpan span) {
        this(name, doubleColon, functional, arguments, span.packed());
    }

    /**
     * Whether a single-colon selector of this name is a pseudo-element rather than a
     * pseudo-class.
     *
     * @param name the name after the colon
     * @return whether it is one of the four legacy pseudo-elements
     * @see <a href="https://www.w3.org/TR/selectors-4/#legacy-aliasing">Selectors Level 4 §3.10 Legacy
     *      Aliases</a>
     */
    public static boolean isLegacyName(String name) {
        return LEGACY.contains(Ascii.lower(name));
    }
}
