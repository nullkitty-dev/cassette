package dev.nullkitty.cassette.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.nullkitty.cassette.diagnostics.Severity;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.serializer.CssSerializer;
import dev.nullkitty.cassette.serializer.Formatting;
import dev.nullkitty.cassette.serializer.SerializerOptions;

/**
 * {@code @import} resolution: what an importer is asked, and what its answer produces.
 *
 * <p>The importer here is a map, which is all cassette ever needs one to be, it hands over a
 * specifier and takes bytes back, and every question about filesystems, roots and networks
 * belongs on the other side of that line.
 */
class BundlerImportTest {

    /**
     * An importer over a map, and a record of what it was asked.
     */
    private static final class Map1 implements Importer {

        private final Map<String, byte[]> files = new LinkedHashMap<>();

        private final List<String> asked = new java.util.ArrayList<>();

        Map1 with(String id, String css) {
            this.files.put(id, css.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        Map1 with(String id, byte[] bytes) {
            this.files.put(id, bytes);
            return this;
        }

        @Override
        public Optional<Source> resolve(String specifier, //
                                        Origin from) {
            this.asked.add(specifier);

            byte[] content = this.files.get(specifier);
            return content == null ? Optional.empty() : Optional.of(new Source(specifier, content));
        }
    }

    private static Source source(String id, //
                                 String css) {
        return new Source(id, css.getBytes(StandardCharsets.UTF_8));
    }

    private static BundleResult bundle(Importer importer, //
                                       String entryCss) {
        return Bundler.bundle(source("entry.css", entryCss), BundleOptions.builder().importer(importer).build());
    }

    private static String minified(BundleResult result) {
        return CssSerializer.serialize(result.ast(),
                                       SerializerOptions.builder().formatting(Formatting.MINIFIED).build());
    }

    @Test
    void inlinesWhatTheImporterResolves() {
        Map1 importer = new Map1().with("base.css", ".base{margin:0}");

        BundleResult result = bundle(importer, "@import url(base.css);a{color:red}");

        assertThat(minified(result)).isEqualTo(".base{margin:0}a{color:red}");
        assertThat(importer.asked).containsExactly("base.css");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsAStringSpecifierAsWellAsAUrl() {
        Map1 importer = new Map1().with("base.css", ".base{margin:0}");

        assertThat(minified(bundle(importer, "@import \"base.css\";"))).isEqualTo(".base{margin:0}");
        assertThat(minified(bundle(importer, "@import url(\"base.css\");"))).isEqualTo(".base{margin:0}");
    }

    @Test
    void tellsTheImporterWhereTheImportSits() {
        Map1 importer = new Map1().with("base.css", "");
        var seen = new java.util.ArrayList<Origin>();
        Importer recording = (specifier, from) -> {
            seen.add(from);
            return importer.resolve(specifier, from);
        };

        Bundler.bundle(source("entry.css", "a{color:red}\n@import url(base.css);"),
                       BundleOptions.builder().importer(recording).build());

        assertThat(seen).containsExactly(new Origin("entry.css", "a{color:red}\n".length()));
    }

    @Test
    void recursesIntoWhatItImported() {
        Map1 importer = new Map1().with("one.css", "@import url(two.css);.one{top:0}").with("two.css", ".two{left:0}");

        BundleResult result = bundle(importer, "@import url(one.css);a{color:red}");

        assertThat(minified(result)).isEqualTo(".two{left:0}.one{top:0}a{color:red}");
        assertThat(importer.asked).containsExactly("one.css", "two.css");
    }

    /**
     * Depth-first, so a source's imported children occupy the space immediately after it, which
     * is why tree order and span order diverge, and why the segment table is printed next to the
     * tree in a bundle golden rather than left to be inferred.
     */
    @Test
    void laysSegmentsOutInDecodeOrderWhichIsNotCascadeOrder() {
        Map1 importer = new Map1().with("base.css", ".base{margin:0}");

        BundleResult result = bundle(importer, "@import url(base.css);a{color:red}");

        assertThat(result.sourceIndex().segments()).extracting(SourceIndex.Segment::sourceId)
                                                   .containsExactly("entry.css", "base.css");
        // The imported rule is first in the tree and second in the space.
        assertThat(result.ast().children().get(0).span().start()).isGreaterThan(result.ast().children().get(1).span()
                                                                                      .start());
    }

    @Test
    void recordsWhichImportPulledEachSourceIn() {
        Map1 importer = new Map1().with("base.css", ".base{margin:0}");

        BundleResult result = bundle(importer, "@import url(base.css);");

        assertThat(result.sourceIndex().segments().get(0).importedFrom()).isNull();
        assertThat(result.sourceIndex().segments().get(1).importedFrom()).isEqualTo(new Origin("entry.css", 0));
    }

    @Nested
    class Wrapping {

        private final Map1 importer = new Map1().with("b.css", ".b{top:0}");

        private String wrapped(String prelude) {
            return minified(bundle(this.importer, "@import " + prelude + ";"));
        }

        @Test
        void splicesABareUrlInDirectly() {
            assertThat(wrapped("url(b.css)")).isEqualTo(".b{top:0}");
        }

        @Test
        void turnsAMediaQueryListIntoAtMedia() {
            assertThat(wrapped("url(b.css) screen")).isEqualTo("@media screen{.b{top:0}}");
            assertThat(wrapped("url(b.css) screen and (min-width:30em)")).isEqualTo("@media screen and (min-width:30em){.b{top:0}}");
        }

        @Test
        void turnsALayerIntoAtLayerNamedOrNot() {
            assertThat(wrapped("url(b.css) layer(base)")).isEqualTo("@layer base{.b{top:0}}");
            assertThat(wrapped("url(b.css) layer")).isEqualTo("@layer{.b{top:0}}");
        }

        /**
         * The shape that would otherwise be silently wrong. {@code supports()} takes a condition
         * <em>or</em> a bare declaration, while {@code @supports} takes a bare condition, so a
         * declaration has to get its parentheses back and a condition must not. Backwards, it
         * produces a condition that is always false, which no assertion about tree shape would
         * catch.
         */
        @Test
        void putsParenthesesBackAroundABareDeclarationAndNotAroundACondition() {
            assertThat(wrapped("url(b.css) supports(display:grid)")).isEqualTo("@supports(display:grid){.b{top:0}}");
            assertThat(wrapped("url(b.css) supports((display:grid) or (display:flex))")).isEqualTo("@supports(display:grid) or (display:flex){.b{top:0}}");
            assertThat(wrapped("url(b.css) supports(not (display:grid))")).isEqualTo("@supports not (display:grid){.b{top:0}}");
        }

        /**
         * Layer outermost, then supports, then media: layer assignment applies to the contents
         * whether or not the conditions match, and the conditions apply within the layer.
         */
        @Test
        void nestsAllFourOutermostFirst() {
            assertThat(wrapped("url(b.css) layer(base) supports(display:grid) screen")).isEqualTo("@layer base{@supports(display:grid){@media screen{.b{top:0}}}}");
        }

        /**
         * A wrapper carries the span of what it wraps, not of the import that caused it. The
         * union of the two would overlap its own siblings and break the parent-contains-children
         * invariant the fuzz suite asserts.
         */
        @Test
        void spansWhatItWrapsAndNotTheImportThatCausedIt() {
            BundleResult result = bundle(this.importer, "@import url(b.css) screen;");

            SourceIndex.Segment imported = result.sourceIndex().segments().get(1);
            assertThat(result.ast().children().get(0).span().start()).isEqualTo(imported.base());
            assertThat(result.ast().children().get(0).span().length()).isEqualTo(imported.length());
        }
    }

    @Nested
    class Declining {

        @Test
        void leavesAnUnresolvedImportInTheOutput() {
            BundleResult result = bundle(new Map1(), "@import url(https://fonts/x.css);a{top:0}");

            assertThat(minified(result)).isEqualTo("@import url(https://fonts/x.css);a{top:0}");

            assertThat(result.diagnostics(Severity.WARNING)).singleElement()
                                                            .satisfies(warning -> assertThat(warning.message()).contains("https://fonts/x.css")
                                                                                                               .contains("left in the output"));
        }

        /**
         * The workflow this makes a one-line importer rather than a wrapper around one: inline
         * the local partials, leave the web font alone.
         */
        @Test
        void letsAnImporterInlineSomeAndDeclineOthers() {
            Map1 importer = new Map1().with("local.css", ".local{top:0}");

            BundleResult result = bundle(importer, "@import url(local.css);@import url(https://fonts/x.css);");

            assertThat(minified(result)).isEqualTo("@import url(https://fonts/x.css);.local{top:0}");
        }

        @Test
        void dropsAnImportThatNamesNoStylesheet() {
            BundleResult result = bundle(new Map1(), "@import screen;a{top:0}");

            assertThat(minified(result)).isEqualTo("a{top:0}");

            assertThat(result.diagnostics(Severity.ERROR)).singleElement()
                                                          .satisfies(error -> assertThat(error.message()).contains("no single stylesheet"));
        }

        @Test
        void dropsAnImportNamingTwoStylesheets() {
            BundleResult result = bundle(new Map1().with("a.css", ""), "@import url(a.css) url(b.css);");

            assertThat(result.diagnostics(Severity.ERROR)).hasSize(1);
        }

        /**
         * The case hoisting must not get wrong. An unresolved import inside a sheet that was
         * itself pulled in conditionally cannot simply move to the top of the bundle; the top is
         * outside the wrapper, so it would stop being conditional. The condition comes with it
         * instead, which the {@code @import} grammar makes exact rather than approximate.
         */
        @Test
        void reattachesAMediaConditionWhenHoistingOutOfAWrapper() {
            Map1 importer = new Map1().with("a.css", "@import url(gone.css);.a{top:0}");

            BundleResult result = bundle(importer, "@import url(a.css) screen;");

            assertThat(minified(result)).isEqualTo("@import url(gone.css) screen;@media screen{.a{top:0}}");
        }

        @Test
        void reattachesANamedLayer() {
            Map1 importer = new Map1().with("a.css", "@import url(gone.css);.a{top:0}");

            BundleResult result = bundle(importer, "@import url(a.css) layer(base);");

            assertThat(minified(result)).isEqualTo("@import url(gone.css) layer(base);@layer base{.a{top:0}}");
        }

        @Test
        void reattachesASupportsCondition() {
            Map1 importer = new Map1().with("a.css", "@import url(gone.css);.a{top:0}");

            BundleResult result = bundle(importer, "@import url(a.css) supports(display:grid);");

            assertThat(minified(result)).isEqualTo("@import url(gone.css) supports(display:grid);"
                                                   + "@supports(display:grid){.a{top:0}}");
        }

        /**
         * All three at once, written in grammar order rather than in nesting order.
         */
        @Test
        void reattachesEveryKindAtOnce() {
            Map1 importer = new Map1().with("a.css", "@import url(gone.css);.a{top:0}");

            BundleResult result = bundle(importer, "@import url(a.css) layer(t) supports(display:grid) print;");

            assertThat(minified(result)).startsWith("@import url(gone.css) layer(t) supports(display:grid) print;");
        }

        /**
         * A {@code @media} outside a {@code @layer} still comes out in grammar order, which is
         * the assertion that would fail if the parts were emitted in the order they were met.
         */
        @Test
        void writesTheSlotsInGrammarOrderNotNestingOrder() {
            Map1 importer = new Map1().with("a.css", "@import url(b.css) layer(inner);.a{top:0}")
                                      .with("b.css", "@import url(gone.css);.b{top:0}");

            BundleResult result = bundle(importer, "@import url(a.css) print;");

            assertThat(minified(result)).startsWith("@import url(gone.css) layer(inner) print;");
        }

        /**
         * Two conditions of one kind would need combining: {@code and} between two media
         * queries, distribution if either has a comma, a dotted path for two layers, and none
         * of that is modelled, so the rule stays where it is.
         */
        @Test
        void refusesTwoMediaConditions() {
            Map1 importer = new Map1().with("a.css", "@import url(b.css) (min-width:30em);.a{top:0}")
                                      .with("b.css", "@import url(gone.css);.b{top:0}");

            BundleResult result = bundle(importer, "@import url(a.css) print;");

            assertThat(minified(result)).contains("@import url(gone.css);");
            assertThat(result.diagnostics(Severity.WARNING)).anySatisfy(warning -> assertThat(warning.message()).contains("cannot be moved onto an @import prelude"));
        }

        /**
         * The rule's own media query counts too, so one wrapper plus one of its own is two.
         */
        @Test
        void refusesAConditionOnTheStrandedImportItself() {
            Map1 importer = new Map1().with("a.css", "@import url(gone.css) print;.a{top:0}");

            BundleResult result = bundle(importer, "@import url(a.css) screen;");

            assertThat(minified(result)).contains("@import url(gone.css) print;");
            assertThat(result.diagnostics(Severity.WARNING)).anySatisfy(warning -> assertThat(warning.message()).contains("cannot be moved onto an @import prelude"));
        }

        /**
         * An anonymous layer is not conservatism; it is impossible. {@code layer} in a prelude
         * creates a <em>new</em> anonymous layer, and anonymous layers are distinct, so a
         * hoisted import would land in a different layer from the block it left, changing cascade
         * order with nothing in the output to show it.
         */
        @Test
        void refusesAnAnonymousLayer() {
            Map1 importer = new Map1().with("a.css", "@import url(gone.css);.a{top:0}");

            BundleResult result = bundle(importer, "@import url(a.css) layer;");

            assertThat(minified(result)).isEqualTo("@layer{@import url(gone.css);.a{top:0}}");
            assertThat(result.diagnostics(Severity.WARNING)).anySatisfy(warning -> assertThat(warning.message()).contains("cannot be moved onto an @import prelude"));
        }

        /**
         * A bare import encloses nothing, so an unresolved import below it hoists normally.
         */
        @Test
        void hoistsFromInsideABareImportWhichWrapsNothing() {
            Map1 importer = new Map1().with("a.css", "@import url(gone.css);.a{top:0}");

            BundleResult result = bundle(importer, "@import url(a.css);");

            assertThat(minified(result)).isEqualTo("@import url(gone.css);.a{top:0}");
        }

        /**
         * With no importer at all, nothing is asked and every import survives.
         */
        @Test
        void resolvesNothingWithoutAnImporter() {
            BundleResult result = Bundler.bundle(List.of(source("a.css", "@import url(b.css);")));

            assertThat(minified(result)).isEqualTo("@import url(b.css);");
            assertThat(result.diagnostics()).isEmpty();
        }
    }

    @Nested
    class Bounds {

        @Test
        void cutsACycleAndNamesTheWholeChain() {
            Map1 importer =
                new Map1().with("a.css", "@import url(b.css);.a{top:0}").with("b.css", "@import url(a.css);.b{left:0}");

            BundleResult result = bundle(importer, "@import url(a.css);");

            assertThat(minified(result)).isEqualTo(".b{left:0}.a{top:0}");

            assertThat(result.diagnostics(Severity.ERROR)).singleElement()
                                                          .satisfies(error -> assertThat(error.message()).contains("entry.css -> a.css -> b.css -> a.css"));
        }

        @Test
        void cutsASourceThatImportsItself() {
            Map1 importer = new Map1().with("loop.css", "@import url(loop.css);.loop{top:0}");

            BundleResult result = bundle(importer, "@import url(loop.css);");

            assertThat(minified(result)).isEqualTo(".loop{top:0}");
            assertThat(result.diagnostics(Severity.ERROR)).hasSize(1);
        }

        /**
         * A diamond is not a cycle. The same file reached twice is inlined twice, because in CSS
         * it genuinely applies twice and the later copy can win the cascade, collapsing it
         * changes what the stylesheet means, which makes it an optimizer's call.
         */
        @Test
        void inlinesADiamondTwiceRatherThanCollapsingIt() {
            Map1 importer = new Map1().with("left.css", "@import url(shared.css);.left{top:0}")
                                      .with("right.css", "@import url(shared.css);.right{top:0}")
                                      .with("shared.css", ".shared{margin:0}");

            BundleResult result = bundle(importer, "@import url(left.css);@import url(right.css);");

            assertThat(minified(result)).isEqualTo(".shared{margin:0}.left{top:0}.shared{margin:0}.right{top:0}");
            assertThat(result.diagnostics()).isEmpty();
        }

        @Test
        void boundsDepthAndReportsItOnce() {
            Map1 importer = new Map1();
            for (int level = 0; level < 10; level++) {
                importer.with(level + ".css", "@import url(" + (level + 1) + ".css);");
            }

            importer.with("10.css", ".deep{top:0}");

            BundleResult result = Bundler.bundle(source("entry.css", "@import url(0.css);"),
                                                 BundleOptions.builder().importer(importer).maxImportDepth(3).build());

            assertThat(result.diagnostics(Severity.ERROR)).singleElement()
                                                          .satisfies(error -> assertThat(error.message()).contains("deeper than 3 levels"));

            assertThat(minified(result)).isEmpty();
        }
    }

    @Nested
    class Encoding {

        /**
         * CSS Syntax §3.2: a sheet that determines nothing for itself inherits the
         * <em>environment encoding</em>, and for a sheet reached through an {@code @import} that
         * is the encoding of the sheet that imported it.
         *
         * <p>Shift_JIS is the right parent for this, for the reason
         * {@code decodesLegacyCjkRatherThanCorruptingIt} already documents: a wrong decode there
         * does not garble, it produces a real backslash and eats the rest of a rule, so the
         * failure is loud.
         */
        @Test
        void makesAnImportInheritItsParentsEncoding() {
            Charset shiftJis = Charset.forName("Shift_JIS");
            Map1 importer = new Map1().with("child.css", ".child{content:\"表\"}".getBytes(shiftJis));

            BundleResult result =
                Bundler.bundle(new Source("parent.css",
                                          "@charset \"shift_jis\";@import url(child.css);".getBytes(shiftJis)),
                               BundleOptions.builder().importer(importer).build());

            assertThat(minified(result)).isEqualTo(".child{content:\"表\"}");
        }

        /**
         * An importer that sets an encoding knows something cassette does not, so it wins.
         */
        @Test
        void letsTheImporterOutrankInheritance() {
            Charset shiftJis = Charset.forName("Shift_JIS");
            Importer importer =
                (specifier,
                 from) -> Optional.of(new Source(specifier,
                                                 ".child{content:\"é\"}".getBytes(StandardCharsets.UTF_8),
                                                 StandardCharsets.UTF_8));

            BundleResult result =
                Bundler.bundle(new Source("parent.css",
                                          "@charset \"shift_jis\";@import url(child.css);".getBytes(shiftJis)),
                               BundleOptions.builder().importer(importer).build());

            assertThat(minified(result)).isEqualTo(".child{content:\"é\"}");
        }

        /**
         * A child's own {@code @charset} outranks what it inherited, being earlier in §3.2.
         */
        @Test
        void letsTheChildsOwnCharsetOutrankWhatItInherited() {
            Charset shiftJis = Charset.forName("Shift_JIS");
            Map1 importer =
                new Map1().with("child.css",
                                "@charset \"utf-8\";.child{content:\"é\"}".getBytes(StandardCharsets.UTF_8));

            BundleResult result =
                Bundler.bundle(new Source("parent.css",
                                          "@charset \"shift_jis\";@import url(child.css);".getBytes(shiftJis)),
                               BundleOptions.builder().importer(importer).build());

            assertThat(minified(result)).isEqualTo(".child{content:\"é\"}");
        }
    }

    /**
     * Idempotence is the only round-trip property a bundle has, since hoisting and inlining mean
     * its output is not the concatenation of its inputs' outputs. Over a graph that exercises
     * every branch of resolution at once.
     */
    @Test
    void isAFixedPointOnItsOwnOutput() {
        Map1 importer = new Map1().with("a.css", "@import url(b.css) screen;.a{top:0}")
                                  .with("b.css", "@charset \"utf-8\";.b{background:rgb(0 0 0;}");

        BundleResult bundled =
            bundle(importer, "@import url(a.css) layer(base);@import url(missing.css);.entry{left:0}");

        for (SerializerOptions options : List.of(SerializerOptions.builder().build(),
                                                 SerializerOptions.builder().formatting(Formatting.MINIFIED).build(),
                                                 SerializerOptions.builder().legacyCompatible().build())) {
            String once = CssSerializer.serialize(bundled.ast(), options);
            String twice = CssSerializer.serialize(CssParser.parse(once).ast(), options);

            assertThat(twice).isEqualTo(once);
        }
    }
}
