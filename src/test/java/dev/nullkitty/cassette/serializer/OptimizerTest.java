package dev.nullkitty.cassette.serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.nullkitty.cassette.ast.ClassSelector;
import dev.nullkitty.cassette.ast.Declaration;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.parser.CssParser;

/**
 * The optimization pass: the transforms that ship, and the driver that runs them.
 */
class OptimizerTest {

    @Nested
    class Transforms {

        @Test
        void compactsNumbers() {
            assertThat(optimized("a{margin:0.500em +5px 1.0px 007}")).isEqualTo("a{margin:.5em 5px 1px 7}");
        }

        @Test
        void leavesExponentsAlone() {
            assertThat(optimized("a{margin:1e2px}")).isEqualTo("a{margin:1e2px}");
        }

        @Test
        void dropsAZeroLengthsUnit() {
            assertThat(optimized("a{margin:0px 0em}")).isEqualTo("a{margin:0 0}");
        }

        @Test
        void keepsAZeroTimesUnit() {
            // '0' is not a <time>; 'transition-duration: 0' does not parse.
            assertThat(optimized("a{transition-duration:0s}")).isEqualTo("a{transition-duration:0s}");
        }

        @Test
        void keepsAZeroPercentage() {
            assertThat(optimized("a{width:0%}")).isEqualTo("a{width:0%}");
        }

        @Test
        void keepsAZeroLengthInsideCalc() {
            // In calc() a unitless zero is a different type: calc(0 + 5%) does not parse.
            assertThat(optimized("a{width:calc(0px + 5%)}")).isEqualTo("a{width:calc(0px + 5%)}");
        }

        @Test
        void shortensHexColors() {
            assertThat(optimized("a{color:#AABBCC;border-color:#FF00FF88;outline-color:#336699}")).isEqualTo("a{color:#abc;border-color:#f0f8;outline-color:#369}");
        }

        @Test
        void lowercasesPropertyAndAtRuleNames() {
            assertThat(optimized("@MEDIA print{a{COLOR:red}}")).isEqualTo("@media print{a{color:red}}");
        }

        @Test
        void leavesACustomPropertyAlone() {
            // Its name is case-sensitive and its value is not this library's to interpret.
            assertThat(optimized("a{--Brand:0px}")).isEqualTo("a{--Brand:0px}");
        }

        @Test
        void leavesValueKeywordsAlone() {
            assertThat(optimized("a{font-family:Arial}")).isEqualTo("a{font-family:Arial}");
        }
    }

    /**
     * The two transforms that remove a node rather than rewriting a value.
     *
     * <p>Both drop metadata the input asserted about itself and that rewriting invalidated.
     * Neither is in {@link Optimizations#all()}, which the first test here is what holds.
     */
    @Nested
    class DroppingStaleMetadata {

        @Test
        void areNotInAll() {
            // Pretty, not minified: MINIFIED strips every comment, so a minified run would
            // pass this whether or not the transform was in all(). The @charset survives
            // either way and is the half that would have caught it regardless.
            String css = "@charset \"shift_jis\";\n/*# sourceMappingURL=a.map */\na{top:0}";

            assertThat(CssSerializer.serialize(Optimizer.optimize(parse(css), Optimizations.all()),
                                               pretty())).contains("@charset").contains("sourceMappingURL");
        }

        @Test
        void dropsACharsetRule() {
            assertThat(with("@charset \"shift_jis\";a{top:0}", Optimizations.dropCharset())).isEqualTo("a{top:0}");
        }

        @Test
        void dropsACharsetNamingUtf8Too() {
            // A stylesheet with nothing declared is UTF-8 by the section 3.2 fallback, so the
            // rule asserts what would be assumed anyway and the bytes are better spent.
            assertThat(with("@charset \"utf-8\";a{top:0}", Optimizations.dropCharset())).isEqualTo("a{top:0}");
        }

        @Test
        void leavesEverythingElseAlone() {
            assertThat(with("@import url(a.css);@media print{a{top:0}}",
                            Optimizations.dropCharset())).isEqualTo("@import url(a.css);@media print{a{top:0}}");
        }

        @Test
        void dropsASourceMappingUrlComment() {
            assertThat(with("/*# sourceMappingURL=a.css.map */\na{top:0}",
                            Optimizations.dropSourceMappingUrl(),
                            pretty())).isEqualTo("a {\n  top: 0;\n}\n");
        }

        @Test
        void dropsTheOlderAtMarkerToo() {
            assertThat(with("/*@ sourceMappingURL=a.css.map */\na{top:0}",
                            Optimizations.dropSourceMappingUrl(),
                            pretty())).doesNotContain("sourceMappingURL");
        }

        @Test
        void keepsAnOrdinaryComment() {
            assertThat(with("/* a note */\na{top:0}",
                            Optimizations.dropSourceMappingUrl(),
                            pretty())).contains("/* a note */");
        }

        @Test
        void keepsACommentThatOnlyMentionsTheName() {
            // Matched case-sensitively and only as the annotation, because acting on anything
            // looser would remove a comment no tool would have honoured.
            assertThat(with("/* see sourcemappingurl docs */\na{top:0}",
                            Optimizations.dropSourceMappingUrl(),
                            pretty())).contains("sourcemappingurl");
        }

        @Test
        void reachesInsideAConditionalGroupRule() {
            // Which is where a bundler puts a conditionally imported sheet, and where a tool
            // scanning for the last annotation in a file would still find one.
            assertThat(with("@media print{/*# sourceMappingURL=a.map */\na{top:0}}",
                            Optimizations.dropSourceMappingUrl(),
                            pretty())).doesNotContain("sourceMappingURL");
        }

        @Test
        void composesWithTheValueOptimizations() {
            List<NodeTransform<?>> both = new ArrayList<>(Optimizations.all());
            both.add(Optimizations.dropCharset());
            both.add(Optimizations.dropSourceMappingUrl());

            assertThat(CssSerializer.serialize(Optimizer.optimize(parse("@charset \"shift_jis\";a{color:#ffffff}"),
                                                                  both),
                                               minifying())).isEqualTo("a{color:#fff}");
        }

        @Test
        void leavesTheTreeAloneWhenThereIsNothingToDrop() {
            // Declining by identity is what keeps an unchanged subtree from being reallocated.
            Stylesheet ast = parse("a{top:0}");

            assertThat(Optimizer.optimize(ast, List.of(Optimizations.dropCharset()))).isSameAs(ast);
        }
    }

    @Nested
    class Driver {

        @Test
        void returnsTheSameTreeWhenNothingIsEnabled() {
            Stylesheet ast = parse("a{margin:0.5px}");

            assertThat(Optimizer.optimize(ast, List.of())).isSameAs(ast);
        }

        @Test
        void returnsTheSameTreeWhenNothingMatched() {
            Stylesheet ast = parse("a{color:red}");

            assertThat(Optimizer.optimize(ast, Optimizations.all())).isSameAs(ast);
        }

        @Test
        void sharesTheSubtreesItDidNotChange() {
            Stylesheet ast = parse("a{color:red}b{margin:0px}");
            Stylesheet optimized = Optimizer.optimize(ast, Optimizations.all());

            assertThat(optimized).isNotSameAs(ast);
            assertThat(optimized.children().get(0)).isSameAs(ast.children().get(0));
            assertThat(optimized.children().get(1)).isNotSameAs(ast.children().get(1));
        }

        @Test
        void chainsTransformsAcrossANodeTypeChange() {
            // '0.0px' loses its unit, becoming a number, and the number transform, which
            // never saw the dimension, still gets its turn at it.
            assertThat(optimized("a{margin:0.0px}")).isEqualTo("a{margin:0}");
        }

        @Test
        void rejectsATransformForANodeTypeItDoesNotWalk() {
            NodeTransform<ClassSelector> selectors = NodeTransform.of(ClassSelector.class, selector -> selector);

            assertThatThrownBy(() -> Optimizer.optimize(parse("a{}"),
                                                        List.of(selectors))).isInstanceOf(IllegalArgumentException.class)
                                                                            .hasMessageContaining("ClassSelector");
        }

        @Test
        void runsACallersOwnTransform() {
            NodeTransform<Declaration> dropTops =
                NodeTransform.of(Declaration.class,
                                 declaration -> "top".equals(declaration.property()) ? new Declaration("inset-block-start",
                                                                                                       declaration.value(),
                                                                                                       declaration.important(),
                                                                                                       declaration.span())
                                                                                     : declaration);

            Stylesheet optimized = Optimizer.optimize(parse("a{top:0}"), List.of(dropTops));

            assertThat(CssSerializer.serialize(optimized, minifying())).isEqualTo("a{inset-block-start:0}");
        }
    }

    // -----------------------------------------------------------------------

    private static String optimized(String css) {
        Stylesheet ast = Optimizer.optimize(parse(css), Optimizations.all());
        return CssSerializer.serialize(ast, minifying());
    }

    private static String with(String css, NodeTransform<?> transform) {
        return with(css, transform, minifying());
    }

    private static String with(String css, NodeTransform<?> transform, SerializerOptions how) {
        return CssSerializer.serialize(Optimizer.optimize(parse(css), List.of(transform)), how);
    }

    private static SerializerOptions pretty() {
        return SerializerOptions.DEFAULTS;
    }

    private static Stylesheet parse(String css) {
        return CssParser.parse(css).ast();
    }

    private static SerializerOptions minifying() {
        return SerializerOptions.builder().formatting(Formatting.MINIFIED).build();
    }
}
