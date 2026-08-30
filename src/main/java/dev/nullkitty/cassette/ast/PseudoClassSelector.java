package dev.nullkitty.cassette.ast;

import java.util.List;
import java.util.Set;

import dev.nullkitty.cassette.text.Ascii;

/**
 * A pseudo-class: {@code :hover}, {@code :nth-child(2n+1)}, {@code :is(.a, .b)}.
 *
 * <p>Functional pseudo-classes split into two kinds, and the split is why this record has
 * both an {@code arguments} list and a {@code selectors} field rather than one of each:
 *
 * <ul>
 *   <li>Those taking a selector list, {@code :is()}, {@code :where()}, {@code :not()},
 *       {@code :has()}, get a parsed {@link SelectorList}. That is not a convenience:
 *       specificity depends on their contents, and a {@code &} nested inside one still has
 *       to be found and expanded.</li>
 *   <li>Everything else keeps raw {@link ComponentValue}s, because {@code :lang(en-GB)} and
 *       {@code :dir(rtl)} are grammars this parser does not know and does not need to.</li>
 * </ul>
 *
 * <p>{@code :nth-child()} and {@code :nth-last-child()} are both: the {@code An+B} part stays
 * in {@code arguments}, and the selector list after {@code of}, if written, is parsed.
 *
 * @param name       the pseudo-class name without its {@code :}, with escapes resolved
 * @param functional whether it was written with parentheses, even empty ones
 * @param arguments  the unparsed arguments, empty when there are none or when they parsed
 *                   into {@code selectors}
 * @param selectors  the parsed selector-list argument, or {@code null} if this pseudo-class
 *                   does not take one
 * @param packedSpan the packed region of source this selector was parsed from, {@code :} included
 * @see <a href="https://www.w3.org/TR/selectors-4/#logical-combination">Selectors Level 4 §4 Logical
 *      Combinations</a>
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#anb-microsyntax">CSS Syntax Level 3 §6 The An+B
 *      microsyntax</a>
 */
public record PseudoClassSelector(String name,
                                  boolean functional,
                                  List<ComponentValue> arguments,
                                  SelectorList selectors,
                                  long packedSpan)
    implements
        SimpleSelector {

    /**
     * Lowercase names of the pseudo-classes whose whole argument is a selector list.
     */
    private static final Set<String> SELECTOR_ARGUMENT = Set.of("is",
                                                                "where",
                                                                "not",
                                                                "has",
                                                                // Pre-standard spellings of :is(), still in the wild and still worth parsing
                                                                // structurally so flattening and specificity see through them.
                                                                "matches", //
                                                                "any",
                                                                "-moz-any",
                                                                "-webkit-any");

    /**
     * Lowercase names of the pseudo-classes taking {@code An+B [of <selector-list>]}.
     */
    private static final Set<String> NTH_OF_SELECTOR = Set.of("nth-child", //
                                                              "nth-last-child");

    /**
     * Copies {@code arguments} so the record is genuinely immutable.
     *
     * @throws NullPointerException if {@code name}, {@code arguments}, {@code span} or any
     *         argument element is {@code null}
     */
    public PseudoClassSelector {
        arguments = List.copyOf(arguments);
    }

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public PseudoClassSelector(String name,
                               boolean functional,
                               List<ComponentValue> arguments,
                               SelectorList selectors,
                               SourceSpan span) {
        this(name, functional, arguments, selectors, span.packed());
    }

    /**
     * Builds a non-functional pseudo-class, as {@code :hover} is.
     *
     * @param name the pseudo-class name without its {@code :}
     * @param span the region of source it was parsed from
     * @return the selector
     */
    public static PseudoClassSelector plain(String name, //
                                            SourceSpan span) {
        return new PseudoClassSelector(name, false, List.of(), null, span);
    }

    /**
     * Whether a pseudo-class of this name takes a selector list as its whole argument.
     *
     * @param name the pseudo-class name without its {@code :}
     * @return whether its arguments should be parsed as selectors
     */
    public static boolean takesSelectorList(String name) {
        return SELECTOR_ARGUMENT.contains(Ascii.lower(name));
    }

    /**
     * Whether a pseudo-class of this name takes {@code An+B} optionally followed by
     * {@code of <selector-list>}.
     *
     * @param name the pseudo-class name without its {@code :}
     * @return whether an {@code of} clause should be parsed as selectors
     * @see <a href="https://www.w3.org/TR/selectors-4/#child-index">Selectors Level 4 §13.3
     *      Child-indexed Pseudo-classes</a>
     */
    public static boolean takesNthOfSelectorList(String name) {
        return NTH_OF_SELECTOR.contains(Ascii.lower(name));
    }

    /**
     * Whether this is {@code :where()}, whose contents contribute no specificity at all.
     *
     * @return whether the name is {@code where}
     */
    public boolean isZeroSpecificity() {
        return Ascii.equalsIgnoreCase(this.name, "where");
    }

    @Override
    public boolean containsNestingSelector() {
        if (this.selectors == null) {
            return false;
        }

        // Indexed rather than a stream, for the reason ComplexSelector's copy gives. This is the
        // recursive step: an argument list here goes back round through ComplexSelector.
        List<ComplexSelector> arguments = this.selectors.selectors();
        for (int at = 0; at < arguments.size(); at++) {
            if (arguments.get(at).containsNestingSelector()) {
                return true;
            }
        }

        return false;
    }
}
