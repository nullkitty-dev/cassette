package dev.nullkitty.cassette.ast;

import java.util.List;

/**
 * A selector list and the block it introduces: {@code .card { color: red }}.
 *
 * <p>{@code body} is a style block's contents in the CSS Nesting Module Level 1 sense, so it
 * mixes three kinds of child in source order, {@link Declaration}s, nested {@link Rule}s,
 * and {@link Comment}s. Order matters and is preserved: a declaration written after a
 * nested rule cascades after it, and moving it would change meaning.
 *
 * @param selectors  the rule's prelude, parsed as a selector list
 * @param body       declarations, nested rules and comments, in source order
 * @param packedSpan the packed region of source this rule was parsed from, prelude through closing brace
 * @see <a href="https://www.w3.org/TR/css-nesting-1/#nesting">CSS Nesting Module Level 1 §3 Nesting
 *      Style Rules</a>
 */
public record StyleRule(SelectorList selectors, //
                        List<Node> body,
                        long packedSpan)
    implements
        Rule {

    /**
     * Copies {@code body} so the record is genuinely immutable.
     *
     * @throws NullPointerException if any argument or child is {@code null}
     */
    public StyleRule {
        body = List.copyOf(body);
    }

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public StyleRule(SelectorList selectors, //
                     List<Node> body,
                     SourceSpan span) {
        this(selectors, body, span.packed());
    }

    /**
     * The rule's own declarations, skipping nested rules and comments.
     *
     * @return the declarations in source order
     */
    public List<Declaration> declarations() {
        return this.body.stream() //
                        .filter(Declaration.class::isInstance) //
                        .map(Declaration.class::cast) //
                        .toList();
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
