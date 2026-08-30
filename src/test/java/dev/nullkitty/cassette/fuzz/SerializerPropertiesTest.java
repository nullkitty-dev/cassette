package dev.nullkitty.cassette.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import dev.nullkitty.cassette.ast.ConditionalGroupRule;
import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.StyleRule;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.serializer.CssSerializer;
import dev.nullkitty.cassette.serializer.Formatting;
import dev.nullkitty.cassette.serializer.NestingExpansion;
import dev.nullkitty.cassette.serializer.NestingMode;
import dev.nullkitty.cassette.serializer.Optimizations;
import dev.nullkitty.cassette.serializer.Optimizer;
import dev.nullkitty.cassette.serializer.SerializerOptions;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/**
 * Invariants the serializer holds for anything the parser can produce, including the trees
 * it produces from input that never parsed cleanly.
 *
 * <p>The round-trip property is stated as <em>idempotence</em>, not as "output re-parses to
 * the input tree". The first serialization of recovered wreckage is allowed to be a
 * normalization, a bad-url token writes nothing, an unclosed block gains its closer, but
 * everything after that has to be a fixed point. A serializer whose output says something
 * different every time it is fed back in has no defensible meaning.
 */
class SerializerPropertiesTest {

    private static final SerializerOptions PRETTY = SerializerOptions.DEFAULTS;

    private static final SerializerOptions MINIFIED = SerializerOptions.builder() //
                                                                       .formatting(Formatting.MINIFIED) //
                                                                       .build();

    private static final SerializerOptions FLATTENED = SerializerOptions.builder() //
                                                                        .nesting(NestingMode.FLATTEN) //
                                                                        .formatting(Formatting.MINIFIED) //
                                                                        .build();

    private static final SerializerOptions LEGACY = SerializerOptions.builder() //
                                                                     .legacyCompatible() //
                                                                     .build();

    @Property
    void neverThrows(@ForAll(supplier = CssLikeArbitraries.Bytes.class) byte[] input) {
        Stylesheet ast = CssParser.parse(input).ast();

        for (SerializerOptions options : List.of(PRETTY, MINIFIED, FLATTENED, LEGACY)) {
            assertThat(CssSerializer.serialize(ast, options)).isNotNull();
        }
    }

    @Property
    void serializingIsIdempotent(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        for (SerializerOptions options : List.of(PRETTY, MINIFIED, FLATTENED, LEGACY)) {
            String once = serialize(input, options);
            String twice = serialize(once, options);

            assertThat(twice).as("re-serializing %s output", options.formatting()).isEqualTo(once);
        }
    }

    @Property
    void optimizedOutputIsStillIdempotent(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        String once = optimize(input);

        assertThat(optimize(once)).isEqualTo(once);
    }

    @Property
    void flatteningLeavesNoNestedRules(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        Stylesheet flattened = CssParser.parse(serialize(input, FLATTENED)).ast();

        for (Node node : rules(flattened)) {
            if (node instanceof StyleRule rule) {
                assertThat(rule.nestedRules()).as("nested rules survived flattening").isEmpty();
            }
        }
    }

    @Property
    void everyOutputReparsesWithoutNewErrors(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        // Recovery is allowed to lose things, but what survives has to be well-formed: a
        // second parse of the output must not find more wrong with it than the first did.
        long before = CssParser.parse(input).diagnostics().size();
        long after = CssParser.parse(serialize(input, PRETTY)).diagnostics().size();

        assertThat(after).isLessThanOrEqualTo(before);
    }

    // -----------------------------------------------------------------------

    private static String serialize(String css, SerializerOptions options) {
        return CssSerializer.serialize(CssParser.parse(css).ast(), options);
    }

    private static String optimize(String css) {
        Stylesheet ast = Optimizer.optimize(CssParser.parse(css).ast(), Optimizations.all());
        return CssSerializer.serialize(ast,
                                       SerializerOptions.builder().nesting(NestingMode.FLATTEN)
                                                        .nestingExpansion(NestingExpansion.IS_WRAP)
                                                        .formatting(Formatting.MINIFIED).build());
    }

    /**
     * Every rule in the sheet, at any depth.
     */
    private static List<Node> rules(Stylesheet stylesheet) {
        List<Node> found = new ArrayList<>();
        collect(stylesheet.children(), found);
        return found;
    }

    private static void collect(List<Node> children, List<Node> found) {
        for (Node child : children) {
            found.add(child);

            switch (child) {
                case StyleRule rule -> collect(rule.body(), found);
                case ConditionalGroupRule rule -> collect(rule.body(), found);
                default -> {
                    // Nothing else holds rules.
                }
            }
        }
    }
}
