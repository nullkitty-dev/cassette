package dev.nullkitty.cassette.serializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.nullkitty.cassette.ast.AtKeywordToken;
import dev.nullkitty.cassette.ast.AtRule;
import dev.nullkitty.cassette.ast.BadStringToken;
import dev.nullkitty.cassette.ast.BadUrlToken;
import dev.nullkitty.cassette.ast.Comment;
import dev.nullkitty.cassette.ast.ComponentValue;
import dev.nullkitty.cassette.ast.ConditionalGroupRule;
import dev.nullkitty.cassette.ast.Declaration;
import dev.nullkitty.cassette.ast.DelimToken;
import dev.nullkitty.cassette.ast.DimensionToken;
import dev.nullkitty.cassette.ast.FunctionValue;
import dev.nullkitty.cassette.ast.HashToken;
import dev.nullkitty.cassette.ast.IdentToken;
import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.NumberToken;
import dev.nullkitty.cassette.ast.PercentageToken;
import dev.nullkitty.cassette.ast.Punctuation;
import dev.nullkitty.cassette.ast.SimpleBlock;
import dev.nullkitty.cassette.ast.StringToken;
import dev.nullkitty.cassette.ast.StyleRule;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.ast.UrlToken;
import dev.nullkitty.cassette.ast.WhitespaceToken;

/**
 * Runs every enabled optimization in one pass over the tree.
 *
 * <pre>{@code
 * Stylesheet optimized = Optimizer.optimize(result.ast(), Optimizations.all());
 * String css = CssSerializer.serialize(optimized, options);
 * }</pre>
 *
 * <p>One pass, not N. AST nodes are immutable records, so a pass that changes anything rebuilds
 * every node above it, and running optimizations one after another would rebuild the whole tree
 * once per optimization. This walks once, offers each node to every transform that asked for its
 * type, and rebuilds a node only when one of them changed something. Allocation is therefore flat
 * in the number of enabled optimizations.
 *
 * <p>Two parts of the tree are not walked:
 *
 * <ul>
 *   <li><b>A custom property's value.</b> It is arbitrary token soup by definition, and the
 *       optimizations that are safe for {@code margin} are guesses about a value whose
 *       meaning is decided somewhere this library cannot see.</li>
 *   <li><b>Selectors.</b> Nothing shipped here rewrites one, and a transform that wants to
 *       can take the {@link StyleRule} and rebuild its prelude. Declaring a selector type is
 *       rejected outright rather than silently never firing.</li>
 * </ul>
 */
public final class Optimizer {

    /**
     * Node classes the walk below visits; anything else is a caller error.
     */
    private static final Set<Class<? extends Node>> WALKED = Set.of(Stylesheet.class,
                                                                    StyleRule.class,
                                                                    AtRule.class,
                                                                    ConditionalGroupRule.class,
                                                                    Declaration.class,
                                                                    IdentToken.class,
                                                                    AtKeywordToken.class,
                                                                    HashToken.class,
                                                                    StringToken.class,
                                                                    BadStringToken.class,
                                                                    UrlToken.class,
                                                                    BadUrlToken.class,
                                                                    DelimToken.class,
                                                                    NumberToken.class,
                                                                    PercentageToken.class,
                                                                    DimensionToken.class,
                                                                    WhitespaceToken.class,
                                                                    Punctuation.class,
                                                                    Comment.class,
                                                                    FunctionValue.class,
                                                                    SimpleBlock.class);

    private final Map<Class<?>, List<NodeTransform<?>>> byType;

    private Optimizer(Map<Class<?>, List<NodeTransform<?>>> byType) {
        this.byType = byType;
    }

    /**
     * Applies {@code enabled} to every node they asked for.
     *
     * @param stylesheet the tree to optimize
     * @param enabled    the optimizations to run; an empty list returns the tree untouched
     * @return the optimized tree, or the original when nothing changed
     * @throws IllegalArgumentException if a transform declares a node type this pass does
     *         not visit
     */
    public static Stylesheet optimize(Stylesheet stylesheet, //
                                      List<NodeTransform<?>> enabled) {
        Objects.requireNonNull(stylesheet, "stylesheet");
        Objects.requireNonNull(enabled, "enabled");

        if (enabled.isEmpty()) {
            return stylesheet;
        }

        return new Optimizer(index(enabled)).stylesheet(stylesheet);
    }

    private static Map<Class<?>, List<NodeTransform<?>>> index(List<NodeTransform<?>> enabled) {
        Map<Class<?>, List<NodeTransform<?>>> byType = new HashMap<>();

        for (NodeTransform<?> transform : enabled) {
            for (Class<? extends Node> type : transform.types()) {
                if (!WALKED.contains(type)) {
                    throw new IllegalArgumentException("this pass does not visit "
                                                       + type.getSimpleName()
                                                       + "; rewrite the rule that holds it instead");
                }

                byType.computeIfAbsent(type, ignored -> new ArrayList<>()).add(transform);
            }
        }

        return byType;
    }

    // -----------------------------------------------------------------------
    // The walk
    // -----------------------------------------------------------------------

    private Stylesheet stylesheet(Stylesheet stylesheet) {
        List<Node> children = nodes(stylesheet.children());
        Stylesheet rebuilt =
            children == stylesheet.children() ? stylesheet : new Stylesheet(children, stylesheet.span());

        return apply(rebuilt);
    }

    private Node node(Node node) {
        return switch (node) {
            case Stylesheet nested -> stylesheet(nested);
            case StyleRule rule -> styleRule(rule);
            case ConditionalGroupRule rule -> groupRule(rule);
            case AtRule rule -> atRule(rule);
            case Declaration declaration -> declaration(declaration);
            case ComponentValue value -> value(value);

            // Selectors are not walked; see the class comment.
            default -> node;
        };
    }

    private Node styleRule(StyleRule rule) {
        List<Node> body = nodes(rule.body());
        StyleRule rebuilt = body == rule.body() ? rule : new StyleRule(rule.selectors(), body, rule.span());

        return apply(rebuilt);
    }

    private Node groupRule(ConditionalGroupRule rule) {
        List<ComponentValue> prelude = values(rule.prelude());
        List<Node> body = nodes(rule.body());

        ConditionalGroupRule rebuilt =
            prelude == rule.prelude() && body == rule.body() ? rule
                                                             : new ConditionalGroupRule(rule.name(),
                                                                                        prelude,
                                                                                        body,
                                                                                        rule.span());

        return apply(rebuilt);
    }

    private Node atRule(AtRule rule) {
        List<ComponentValue> prelude = values(rule.prelude());
        List<ComponentValue> block = rule.isStatement() ? null : values(rule.block());

        AtRule rebuilt =
            prelude == rule.prelude() && block == rule.block() ? rule
                                                               : new AtRule(rule.name(), prelude, block, rule.span());

        return apply(rebuilt);
    }

    private Node declaration(Declaration declaration) {
        if (declaration.isCustomProperty()) {
            return apply(declaration);
        }

        List<ComponentValue> value = values(declaration.value());
        Declaration rebuilt = value == declaration.value() ? declaration
                                                           : new Declaration(declaration.property(),
                                                                             value,
                                                                             declaration.important(),
                                                                             declaration.span());

        return apply(rebuilt);
    }

    private ComponentValue value(ComponentValue value) {
        ComponentValue rebuilt = switch (value) {
            case FunctionValue function -> {
                List<ComponentValue> arguments = values(function.arguments());
                yield arguments == function.arguments() ? function
                                                        : new FunctionValue(function.name(),
                                                                            arguments,
                                                                            function.span());
            }

            case SimpleBlock block -> {
                List<ComponentValue> contents = values(block.contents());
                yield contents == block.contents() ? block : new SimpleBlock(block.open(), contents, block.span());
            }

            default -> value;
        };

        return apply(rebuilt);
    }

    /**
     * Rebuilds a child list only if some child changed, so an untouched subtree stays shared.
     */
    private List<Node> nodes(List<Node> items) {
        List<Node> rebuilt = null;

        for (int index = 0; index < items.size(); index++) {
            Node before = items.get(index);
            Node after = node(before);
            if (rebuilt == null && after != before) {
                rebuilt = new ArrayList<>(items.size());
                rebuilt.addAll(items.subList(0, index));
            }

            if (rebuilt != null) {
                rebuilt.add(after);
            }
        }

        return rebuilt == null ? items : rebuilt;
    }

    private List<ComponentValue> values(List<ComponentValue> items) {
        List<ComponentValue> rebuilt = null;

        for (int index = 0; index < items.size(); index++) {
            ComponentValue before = items.get(index);
            ComponentValue after = value(before);
            if (rebuilt == null && after != before) {
                rebuilt = new ArrayList<>(items.size());
                rebuilt.addAll(items.subList(0, index));
            }

            if (rebuilt != null) {
                rebuilt.add(after);
            }
        }

        return rebuilt == null ? items : rebuilt;
    }

    /**
     * Offers one node to every transform that asked for its type.
     *
     * <p>A transform may return a node of a different type, dropping a zero length's unit
     * turns a dimension into a number, and the new type's own transforms have not run on
     * it, so the lookup repeats until the type settles. The visited set is what stops two
     * transforms that undo each other from spinning.
     */
    private <T extends Node> T apply(T node) {
        T current = node;
        Set<Class<?>> seen = null;

        while (true) {
            Class<?> type = current.getClass();
            List<NodeTransform<?>> transforms = this.byType.get(type);
            if (transforms == null) {
                return current;
            }

            for (NodeTransform<?> transform : transforms) {
                @SuppressWarnings("unchecked") // registered under the class of the node passed in
                NodeTransform<T> typed = (NodeTransform<T>) transform;
                current = Objects.requireNonNull(typed.apply(current),
                                                 "a transform returned null; return the node unchanged to decline");
            }

            if (current.getClass() == type) {
                return current;
            }

            if (seen == null) {
                seen = new HashSet<>();
            }

            if (!seen.add(type)) {
                return current;
            }
        }
    }
}
