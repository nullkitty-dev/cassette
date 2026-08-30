package dev.nullkitty.cassette.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import dev.nullkitty.cassette.ast.AtRule;
import dev.nullkitty.cassette.ast.ComponentValue;
import dev.nullkitty.cassette.ast.ConditionalGroupRule;
import dev.nullkitty.cassette.ast.Declaration;
import dev.nullkitty.cassette.ast.FunctionValue;
import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.SimpleBlock;
import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.ast.StyleRule;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.parser.ParseResult;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/**
 * Invariants the parser holds for <em>any</em> input, valid or not.
 *
 * <p>Golden files cover the inputs someone thought to write down. These cover the ones
 * nobody did, which is where the escape and charset edge cases live, and where a parser
 * that recovers by looping forever or by handing back a span nothing can slice would
 * otherwise go unnoticed until a real stylesheet hit it.
 *
 * <p>The round-trip properties, that output re-parses to itself without new errors, need a
 * serializer to round-trip through and are in {@link SerializerPropertiesTest}.
 */
class ParserPropertiesTest {

    @Property
    void neverThrows(@ForAll(supplier = CssLikeArbitraries.Bytes.class) byte[] input) {
        ParseResult result = CssParser.parse(input);

        assertThat(result.ast()).isNotNull();
        assertThat(result.diagnostics()).isNotNull();
    }

    @Property
    void everySpanLiesInsideTheInput(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        ParseResult result = CssParser.parse(input);
        int length = result.ast().span().length();

        for (Node node : flatten(result.ast())) {
            assertSpanFits(node.span(), length, node);
        }

        for (Diagnostic diagnostic : result.diagnostics()) {
            assertSpanFits(diagnostic.span(), length, diagnostic);
        }
    }

    @Property
    void everyDeclarationValueIsTrimmed(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        for (Node node : flatten(CssParser.parse(input).ast())) {
            if (!(node instanceof Declaration declaration) || declaration.value().isEmpty()) {
                continue;
            }

            List<ComponentValue> value = declaration.value();

            assertThat(value.get(0).isTrivia()).as("leading whitespace in %s", declaration.property()).isFalse();

            assertThat(value.get(value.size()
                                 - 1)).as("trailing whitespace in %s", declaration.property())
                                      .matches(last -> !(last instanceof dev.nullkitty.cassette.ast.WhitespaceToken));
        }
    }

    @Property
    void aParentSpanCoversItsChildren(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        Stylesheet sheet = CssParser.parse(input).ast();

        for (Node node : flatten(sheet)) {
            for (Node child : childrenOf(node)) {
                assertThat(child.span().start()).as("%s starts before its parent %s", child, node)
                                                .isGreaterThanOrEqualTo(node.span().start());

                assertThat(child.span().end()).as("%s ends after its parent %s", child, node)
                                              .isLessThanOrEqualTo(node.span().end());
            }
        }
    }

    private static void assertSpanFits(SourceSpan span, int length, Object owner) {
        assertThat(span.start()).as("%s starts outside the input", owner).isBetween(0, length);
        assertThat(span.end()).as("%s ends outside the input", owner).isBetween(span.start(), length);
    }

    /**
     * Every node in the tree, parents before children.
     */
    private static List<Node> flatten(Node root) {
        List<Node> all = new ArrayList<>();
        collect(root, all);
        return all;
    }

    private static void collect(Node node, List<Node> into) {
        into.add(node);
        for (Node child : childrenOf(node)) {
            collect(child, into);
        }
    }

    /**
     * The children of a node, for the two properties that walk the tree.
     *
     * <p>Selectors are left out. Their spans are asserted by the golden dumps, and walking into them
     * here would double the surface for no extra signal.
     */
    private static List<Node> childrenOf(Node node) {
        return switch (node) {
            case Stylesheet stylesheet -> stylesheet.children();
            case StyleRule rule -> rule.body();
            case ConditionalGroupRule rule -> concat(rule.prelude(), rule.body());
            case AtRule rule -> rule.isStatement() ? List.copyOf(rule.prelude()) : concat(rule.prelude(), rule.block());
            case Declaration declaration -> List.copyOf(declaration.value());
            case FunctionValue function -> List.copyOf(function.arguments());
            case SimpleBlock block -> List.copyOf(block.contents());
            default -> List.of();
        };
    }

    private static List<Node> concat(List<? extends Node> first, List<? extends Node> second) {
        List<Node> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
