package dev.nullkitty.cassette.ast;

import java.util.List;
import java.util.Set;

import dev.nullkitty.cassette.text.Ascii;

/**
 * An at-rule whose block holds rules rather than opaque tokens: {@code @media},
 * {@code @supports}, {@code @container}, {@code @layer}.
 *
 * <p>The prelude stays opaque, since a media query or a {@code @supports} condition is a grammar
 * this parser does not evaluate. The block is parsed structurally, because CSS Nesting Level 1 lets
 * these contain nested style rules and flattening has to walk into them.
 *
 * <p>{@code body} is a {@code List<Node>} rather than a {@code List<Rule>} for two reasons.
 * Comments are real nodes and have to live somewhere, and a conditional group rule nested inside a
 * style rule may contain bare {@link Declaration}s that apply to the parent's selector.
 *
 * @param name       the at-keyword without its {@code @}, with ASCII case preserved
 * @param prelude    the media query, support condition, container query or layer name, unparsed
 * @param body       nested rules, declarations and comments, in source order
 * @param packedSpan the packed region of source this rule was parsed from
 * @see <a href="https://www.w3.org/TR/css-conditional-3/#contents-of">CSS Conditional Rules Level 3 §3
 *      Contents of conditional group rules</a>
 * @see <a href="https://www.w3.org/TR/css-nesting-1/#conditionals">CSS Nesting Module Level 1 §3.3
 *      Nesting Other At-Rules</a>
 */
public record ConditionalGroupRule(String name, //
                                   List<ComponentValue> prelude,
                                   List<Node> body,
                                   long packedSpan)
    implements
        Rule {

    /**
     * The at-keywords, lowercased, whose blocks parse as rules rather than as token soup.
     */
    private static final Set<String> NAMES = Set.of("media", //
                                                    "supports",
                                                    "container",
                                                    "layer");

    /**
     * Copies the lists so the record is genuinely immutable.
     *
     * @throws NullPointerException if any argument or list element is {@code null}
     */
    public ConditionalGroupRule {
        prelude = List.copyOf(prelude);
        body = List.copyOf(body);
    }

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public ConditionalGroupRule(String name, //
                                List<ComponentValue> prelude,
                                List<Node> body,
                                SourceSpan span) {
        this(name, prelude, body, span.packed());
    }

    /**
     * Whether an at-rule of this name has a block of rules.
     *
     * <p>{@code @layer} appears here even though its statement form ({@code @layer a, b;})
     * has no block at all, that form parses as an {@link AtRule}, since there is nothing to
     * recurse into.
     *
     * @param name the at-keyword without its {@code @}
     * @return whether the at-rule is a conditional group rule
     */
    public static boolean isConditionalGroupName(String name) {
        return NAMES.contains(Ascii.lower(name));
    }

    /**
     * The rules nested directly inside this one.
     *
     * @return the nested rules in source order
     */
    public List<Rule> nestedRules() {
        return this.body.stream() //
                        .filter(Rule.class::isInstance) //
                        .map(Rule.class::cast) //
                        .toList();
    }
}
