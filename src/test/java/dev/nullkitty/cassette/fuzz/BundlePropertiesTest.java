package dev.nullkitty.cassette.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.nullkitty.cassette.ast.AtRule;
import dev.nullkitty.cassette.ast.ComponentValue;
import dev.nullkitty.cassette.ast.ConditionalGroupRule;
import dev.nullkitty.cassette.ast.Declaration;
import dev.nullkitty.cassette.ast.FunctionValue;
import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.SimpleBlock;
import dev.nullkitty.cassette.ast.StyleRule;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.bundle.BundleOptions;
import dev.nullkitty.cassette.bundle.BundleResult;
import dev.nullkitty.cassette.bundle.Bundler;
import dev.nullkitty.cassette.bundle.Importer;
import dev.nullkitty.cassette.bundle.Source;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.serializer.CssSerializer;
import dev.nullkitty.cassette.serializer.Formatting;
import dev.nullkitty.cassette.serializer.SerializerOptions;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * Invariants bundling holds for <em>any</em> import graph, cycles and all.
 *
 * <p>The graph is generated from a list of integers rather than from CSS: each source imports
 * the sources its numbers name, modulo the source count, so cycles, self-imports, diamonds and
 * deep chains all arise on their own and roughly in proportion to how likely they are to be
 * written by accident. What is being tested is the resolution machinery and not the parser, so the
 * CSS around the imports is trivial.
 */
class BundlePropertiesTest {

    private static final int SOURCES = 6;

    /**
     * The property the cycle and depth bounds exist for. An import graph is arbitrary and a
     * bundler that trusted it would recurse forever on the first loop; jqwik will find one
     * within a handful of tries, so this failing looks like a hang rather than a red test,
     * which is exactly why the bounds are checked by a property and not only by an example.
     */
    @Property
    void terminatesOnAnyImportGraph(@ForAll @Size(min = 0, max = 12) List<@IntRange(min = 0, max = 5) Integer> edges) {
        BundleResult result = bundle(graph(edges));

        assertThat(result.ast()).isNotNull();
        assertThat(result.diagnostics()).isNotNull();
    }

    /**
     * Every span lies in the bundle, and every <em>leaf</em> resolves to exactly one source.
     *
     * <p>Leaves rather than all nodes, and the distinction is a finding rather than a
     * convenience. A token always came from one file. A group rule the bundler synthesized to
     * wrap an imported sheet covers that sheet <em>and everything it imported</em>, so it spans
     * several segments and {@code resolve} refuses it, correctly, since it came from no single
     * source. This property is what established that; it was written asserting every node and
     * failed on the first graph with a nested import.
     */
    @Property
    void everyLeafResolvesToOneSource(@ForAll @Size(
        min = 0,
        max = 12) List<@IntRange(min = 0, max = 5) Integer> edges) {
        BundleResult result = bundle(graph(edges));

        for (Node node : flatten(result.ast())) {
            assertThat(node.span().end()).as("%s ends outside the bundle", node)
                                         .isLessThanOrEqualTo(result.sourceIndex().length());
            if (childrenOf(node).isEmpty()) {
                assertThat(result.sourceIndex().resolve(node.span())).as("%s resolves", node).isNotNull();
            }
        }
    }

    /**
     * A parent's span covers its body children's, which is the assertion that catches a
     * synthesized wrapper given the wrong span.
     *
     * <p><em>Body</em> children, because a wrapper is assembled from two files: its contents
     * come from the imported sheet and its prelude is re-emitted from the importing one, whose
     * tokens keep their own spans, that is where the author wrote the media query and where a
     * diagnostic about it should point. So a wrapper's prelude lies outside its span by
     * construction. This property found that too, and the answer was to state it rather than to
     * rewrite the spans and lose the provenance.
     */
    @Property
    void aParentSpanCoversItsBodyChildren(@ForAll @Size(
        min = 0,
        max = 12) List<@IntRange(min = 0, max = 5) Integer> edges) {
        for (Node node : flatten(bundle(graph(edges)).ast())) {
            for (Node child : bodyOf(node)) {
                assertThat(child.span().start()).as("%s starts before its parent %s", child, node)
                                                .isGreaterThanOrEqualTo(node.span().start());

                assertThat(child.span().end()).as("%s ends after its parent %s", child, node)
                                              .isLessThanOrEqualTo(node.span().end());
            }
        }
    }

    /**
     * Hoisting and inlining mean a bundle's output is not the concatenation of its inputs'
     * outputs, so idempotence is the only round-trip property available, the same reasoning
     * the serializer property already settled.
     */
    @Property
    void serializingIsAFixedPoint(@ForAll @Size(min = 0, max = 12) List<@IntRange(min = 0, max = 5) Integer> edges) {
        Stylesheet ast = bundle(graph(edges)).ast();

        for (SerializerOptions options : List.of(SerializerOptions.builder().build(),
                                                 SerializerOptions.builder().formatting(Formatting.MINIFIED).build())) {
            String once = CssSerializer.serialize(ast, options);
            String twice = CssSerializer.serialize(CssParser.parse(once).ast(), options);

            assertThat(twice).isEqualTo(once);
        }
    }

    /**
     * Builds {@code SOURCES} stylesheets whose imports are the generated edges, spread over them
     * in turn. Every wrapping shape is used, so a wrapper's span is exercised as much as a bare
     * splice is.
     */
    private static Map<String, String> graph(List<Integer> edges) {
        List<StringBuilder> files = new ArrayList<>();
        for (int at = 0; at < SOURCES; at++) {
            files.add(new StringBuilder());
        }

        String[] preludes = { "", " screen", " layer(base)", " supports(display: grid)", " layer" };

        for (int at = 0; at < edges.size(); at++) {
            files.get(at % SOURCES).append("@import url(").append(edges.get(at) % SOURCES).append(".css)")
                 .append(preludes[at % preludes.length]).append(";\n");
        }

        Map<String, String> graph = new HashMap<>();
        for (int at = 0; at < SOURCES; at++) {
            graph.put(at + ".css", files.get(at).append(".r").append(at).append("{top:0}\n").toString());
        }

        return graph;
    }

    private static BundleResult bundle(Map<String, String> graph) {
        Importer importer =
            (specifier, from) -> Optional.ofNullable(graph.get(specifier))
                                         .map(css -> new Source(specifier, css.getBytes(StandardCharsets.UTF_8)));
        Source entry = new Source("0.css", graph.get("0.css").getBytes(StandardCharsets.UTF_8));
        return Bundler.bundle(entry, BundleOptions.builder().importer(importer).build());
    }

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
     * Children a node genuinely contains, which for a group rule excludes its prelude.
     */
    private static List<Node> bodyOf(Node node) {
        return switch (node) {
            case ConditionalGroupRule rule -> List.copyOf(rule.body());
            case AtRule ignored -> List.of();
            default -> childrenOf(node);
        };
    }

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

    private static List<Node> concat(List<? extends ComponentValue> first, List<? extends Node> second) {
        List<Node> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
