package dev.nullkitty.cassette.serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.nullkitty.cassette.ast.ConditionalGroupRule;
import dev.nullkitty.cassette.ast.Declaration;
import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.Rule;
import dev.nullkitty.cassette.ast.SelectorList;
import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.ast.StyleRule;
import dev.nullkitty.cassette.ast.Stylesheet;

/**
 * Rewrites a nested stylesheet into a flat one: every style rule at the top level (or
 * directly inside a conditional group rule), every selector absolute.
 *
 * <p>Three things happen here:
 *
 * <ul>
 *   <li><b>Selectors are absolutized</b> against the enclosing rule, {@link NestingExpander}
 *       does that part.</li>
 *   <li><b>A rule is split where a nested rule interrupts its declarations.</b>
 *       {@code .a { color: red; .b { } background: blue }} becomes three rules, in that
 *       order, because a declaration written after a nested rule cascades after it and
 *       hoisting it back up would change which one wins.</li>
 *   <li><b>A nested conditional group rule is hoisted out</b> and its contents wrapped in
 *       the parent's selector: {@code .a { @media print { color: red } }} becomes
 *       {@code @media print { .a { color: red } }}.</li>
 * </ul>
 *
 * <p>What does not happen: a group rule nested inside another group rule stays nested. Merging
 * {@code @media} conditions means evaluating media queries, which this library does not parse, and
 * every engine that understands {@code @media} at all has understood nested ones since CSS 2.1. A
 * top-level {@code &} is also left alone, since it has no parent to stand for and rewriting it
 * would be a guess about what the embedding document means by it.
 *
 * <p>Tree in, tree out, like {@link Optimizer}. {@link CssSerializer} runs this itself under
 * {@link NestingMode#FLATTEN}, so a direct call is for the cases where the flattened tree is the
 * result, such as serializing one parse both ways or inspecting absolutized selectors:
 *
 * <pre>{@code
 * Stylesheet ast  = CssParser.parse(bytes).ast();
 * Stylesheet flat = Flattener.flatten(ast, NestingExpansion.IS_WRAPPED);
 * }</pre>
 *
 * <p>Compute specificity on the result rather than on the input. {@code Specificity.of} counts a
 * {@code &} as zero, because its real weight is that of the enclosing rule's selector list, which
 * a node with no parent pointer cannot see. After flattening there is no {@code &} left to
 * undercount.
 *
 * @see <a href="https://www.w3.org/TR/css-nesting-1/#nesting">CSS Nesting Module Level 1 §3 Nesting
 *      Style Rules</a>
 * @see <a href="https://www.w3.org/TR/css-nesting-1/#conditionals">CSS Nesting Module Level 1 §3.3
 *      Nesting Other At-Rules</a>
 */
public final class Flattener {

    private final NestingExpansion expansion;

    private Flattener(NestingExpansion expansion) {
        this.expansion = expansion;
    }

    /**
     * Flattens a whole stylesheet.
     *
     * <p>The tree passed in is not modified; every node is immutable, and an untouched
     * subtree is shared with the result rather than copied.
     *
     * @param stylesheet the parsed stylesheet, nesting and all
     * @param expansion  how {@code &} is expanded
     * @return an equivalent stylesheet with no nested rules
     * @throws NullPointerException if either argument is {@code null}
     */
    public static Stylesheet flatten(Stylesheet stylesheet, //
                                     NestingExpansion expansion) {
        Objects.requireNonNull(stylesheet, "stylesheet");
        Objects.requireNonNull(expansion, "expansion");

        Flattener flattener = new Flattener(expansion);
        List<Node> children = new ArrayList<>(stylesheet.children().size());
        for (Node child : stylesheet.children()) {
            flattener.child(child, null, children);
        }

        return new Stylesheet(children, stylesheet.span());
    }

    /**
     * @param parent the selector every rule emitted from here hangs off, or {@code null} at
     *               the top level of the stylesheet
     */
    private void child(Node node,
                       SelectorList parent, //
                       List<Node> out) {
        switch (node) {
            case StyleRule rule -> styleRule(rule, parent, out);

            case ConditionalGroupRule group -> groupRule(group, parent, out);

            // An at-rule this parser keeps opaque cannot contain a nested style rule, and a
            // comment or a stray declaration has nothing to flatten either.
            default -> out.add(node);
        }
    }

    private void styleRule(StyleRule rule, //
                           SelectorList parent,
                           List<Node> out) {
        if (parent == null && rule.nestedRules().isEmpty()) {
            out.add(rule);
            return;
        }

        SelectorList selectors =
            parent == null ? rule.selectors() : NestingExpander.absolutize(rule.selectors(), parent, this.expansion);

        List<Node> run = new ArrayList<>();

        for (Node item : rule.body()) {
            if (item instanceof Rule nested) {
                emit(selectors, run, rule.span(), out);
                child(nested, selectors, out);
            }
            else {
                run.add(item);
            }
        }

        emit(selectors, run, rule.span(), out);
    }

    private void groupRule(ConditionalGroupRule group, //
                           SelectorList parent,
                           List<Node> out) {
        List<Node> body = new ArrayList<>();
        List<Node> run = new ArrayList<>();

        for (Node item : group.body()) {
            if (item instanceof Rule nested) {
                emit(parent, run, group.span(), body);
                child(nested, parent, body);
            }
            else {
                run.add(item);
            }
        }

        emit(parent, run, group.span(), body);

        out.add(new ConditionalGroupRule(group.name(), group.prelude(), body, group.span()));
    }

    /**
     * Writes out one run of declarations as a rule, and clears it.
     *
     * <p>A run of nothing but comments produces no rule; there is no selector they belong
     * to, so they are emitted where they stood.
     */
    private void emit(SelectorList selectors, //
                      List<Node> run,
                      SourceSpan span,
                      List<Node> out) {
        if (run.isEmpty()) {
            return;
        }

        if (selectors == null || !declares(run)) {
            out.addAll(run);
        }
        else {
            out.add(new StyleRule(selectors, List.copyOf(run), span));
        }

        run.clear();
    }

    /**
     * Whether a run holds anything a rule would be built for.
     *
     * <p>An indexed loop rather than a stream, and behind the {@code selectors == null}
     * short circuit rather than beside it: this runs once per run of declarations per
     * flattened rule, and a stream pipeline allocated there is a pipeline allocated for
     * every rule in the sheet.
     */
    private static boolean declares(List<Node> run) {
        for (int index = 0; index < run.size(); index++) {
            if (run.get(index) instanceof Declaration) {
                return true;
            }
        }

        return false;
    }
}
