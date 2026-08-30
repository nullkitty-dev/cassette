package dev.nullkitty.cassette.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.nullkitty.cassette.ast.AtRule;
import dev.nullkitty.cassette.ast.Comment;
import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.ast.StyleRule;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.diagnostics.Severity;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.serializer.CssSerializer;
import dev.nullkitty.cassette.serializer.Formatting;
import dev.nullkitty.cassette.serializer.SerializerOptions;

/**
 * Concatenation: several sources into one tree, in cascade order.
 *
 * <p>What is worth asserting is the part concatenation adds, the layout, the prologue, the
 * banners, and never what the parser or serializer then did with the result, which their own
 * tests already cover.
 */
class BundlerTest {

    private static Source source(String id, String css) {
        return new Source(id, css.getBytes(StandardCharsets.UTF_8));
    }

    private static String minified(BundleResult result) {
        return CssSerializer.serialize(result.ast(),
                                       SerializerOptions.builder() //
                                                        .formatting(Formatting.MINIFIED) //
                                                        .build());
    }

    @Test
    void concatenatesInCascadeOrder() {
        BundleResult result = Bundler.bundle(List.of(source("reset.css", "a{color:red}"), //
                                                     source("app.css", "a{color:blue}")));

        assertThat(minified(result)).isEqualTo("a{color:red}a{color:blue}");
        assertThat(result.hasErrors()).isFalse();
    }

    /**
     * The whole reason spans are threaded through decoding rather than rewritten afterwards:
     * every node in the result still knows which file wrote it.
     */
    @Test
    void keepsEveryNodeTraceableToTheSourceThatWroteIt() {
        BundleResult result = Bundler.bundle(List.of(source("reset.css", "a{color:red}"), //
                                                     source("app.css", ".b{top:0}")));

        List<Node> rules = result.ast().children();
        SourceIndex index = result.sourceIndex();
        assertThat(index.resolve(rules.get(0).span()).sourceId()).isEqualTo("reset.css");
        assertThat(index.resolve(rules.get(1).span()).sourceId()).isEqualTo("app.css");
        assertThat(index.textOf(rules.get(1).span())).isEqualTo(".b{top:0}");

        // Each resolves to the offset it would have had parsed on its own.
        assertThat(index.resolve(rules.get(1).span()).offset()).isZero();
    }

    @Test
    void bundlingOneSourceIsAParseWithTheSameSpans() {
        String css = "@media print { .a > .b { color: red } }";

        BundleResult bundled = Bundler.bundle(source("one.css", css), BundleOptions.DEFAULTS);

        assertThat(bundled.ast().children().toString()).isEqualTo(CssParser.parse(css).ast().children().toString());
    }

    /**
     * Each source is decoded on its own; there is no single bundle encoding.
     */
    @Test
    void decodesEverySourceOnItsOwn() {
        Charset shiftJis = Charset.forName("Shift_JIS");
        BundleResult result =
            Bundler.bundle(List.of(new Source("utf8.css", "a{content:\"é\"}".getBytes(StandardCharsets.UTF_8)),
                                   new Source("sjis.css", "b{content:\"表\"}".getBytes(shiftJis), shiftJis)));

        assertThat(minified(result)).isEqualTo("a{content:\"é\"}b{content:\"表\"}");
    }

    /**
     * A multi-byte source is the case that separates a right answer from a lucky one: its byte
     * count and its character count differ, so a base taken from the wrong one puts every later
     * span in the wrong file.
     */
    @Test
    void measuresASegmentInCharactersAndNotInBytes() {
        String first = "a{content:\"日本語テキスト\"}";
        BundleResult result = Bundler.bundle(List.of(source("wide.css", first), //
                                                     source("after.css", ".b{top:0}")));

        assertThat(first.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(first.length());
        assertThat(result.sourceIndex().segments().get(1).base()).isEqualTo(first.length());

        assertThat(result.sourceIndex()
                         .resolve(result.ast().children().get(1).span())).isEqualTo(new Origin("after.css", 0));
    }

    @Nested
    class Prologue {

        /**
         * Silent by design. One that was honoured did its work at decode time, and one that was
         * not has already been reported by the decode that could not honour it, so a third
         * message here would only ever repeat something or say nothing.
         */
        @Test
        void dropsEveryCharsetWithoutSayingSo() {
            BundleResult result = Bundler.bundle(List.of(source("a.css", "@charset \"utf-8\";a{color:red}"),
                                                         source("b.css", "@charset \"utf-8\";.b{top:0}")));

            assertThat(minified(result)).isEqualTo("a{color:red}.b{top:0}");
            assertThat(result.diagnostics()).isEmpty();
        }

        @Test
        void hoistsAnImportStrandedByConcatenation() {
            BundleResult result = Bundler.bundle(List.of(source("a.css", "a{color:red}"),
                                                         source("b.css", "@import url(x.css);.b{top:0}")));

            assertThat(minified(result)).isEqualTo("@import url(x.css);a{color:red}.b{top:0}");

            assertThat(result.diagnostics(Severity.WARNING)).singleElement()
                                                            .satisfies(warning -> assertThat(warning.message()).contains("past 1 rule")
                                                                                                               .contains("only be preceded by @charset and @layer"));
        }

        /**
         * The first source's leading import is already where it belongs. Warning about it would
         * put a diagnostic on every bundle whose first file imports anything, which is most of
         * them, and a warning that fires on the correct case teaches people to ignore it.
         */
        @Test
        void saysNothingWhenAnImportWasAlreadyAtTheFront() {
            BundleResult result = Bundler.bundle(List.of(source("a.css", "@import url(x.css);a{color:red}"),
                                                         source("b.css", ".b{top:0}")));

            assertThat(minified(result)).isEqualTo("@import url(x.css);a{color:red}.b{top:0}");
            assertThat(result.diagnostics()).isEmpty();
        }

        @Test
        void keepsHoistedImportsInFirstSeenOrder() {
            BundleResult result = Bundler.bundle(List.of(source("a.css", "a{color:red}@import url(one.css);"),
                                                         source("b.css", "@import url(two.css);.b{top:0}")));

            assertThat(minified(result)).isEqualTo("@import url(one.css);@import url(two.css);a{color:red}.b{top:0}");
            assertThat(result.diagnostics(Severity.WARNING)).hasSize(2);
        }
    }

    @Nested
    class Banners {

        @Test
        void namesEachSourceAtItsBoundary() {
            BundleResult result = Bundler.bundle(List.of(source("a.css", "a{color:red}"), //
                                                         source("b.css", ".b{top:0}")),
                                                 BundleOptions.builder() //
                                                              .banners(true) //
                                                              .build());

            assertThat(CssSerializer.serialize(result.ast(),
                                               SerializerOptions.builder().build())).contains("/* a.css */")
                                                                                    .contains("/* b.css */");
        }

        /**
         * Free everywhere else because it is an ordinary AST comment: no serializer change, no
         * new fixture axis, and minification strips it exactly as it strips any other comment.
         */
        @Test
        void isStrippedByMinificationLikeAnyOtherComment() {
            BundleResult result = Bundler.bundle(List.of(source("a.css", "a{color:red}")), //
                                                 BundleOptions.builder() //
                                                              .banners(true) //
                                                              .build());

            assertThat(result.ast().children().get(0)).isInstanceOf(Comment.class);
            assertThat(minified(result)).isEqualTo("a{color:red}");
        }

        /**
         * An id is caller-supplied text, and one that closes its own comment eats the file.
         */
        @Test
        void escapesAnIdThatWouldTerminateItsOwnComment() {
            BundleResult result = Bundler.bundle(List.of(source("evil*/.css", "a{color:red}")),
                                                 BundleOptions.builder() //
                                                              .banners(true) //
                                                              .build());

            String css = CssSerializer.serialize(result.ast(), SerializerOptions.builder().build());
            assertThat(css).contains("evil*\\/.css");

            // The proof: it re-parses as one comment and one rule, not as wreckage.
            assertThat(CssParser.parse(css).ast().children()).hasSize(2);
            assertThat(CssParser.parse(css).diagnostics()).isEmpty();
        }

        /**
         * A banner is synthesized and has no text of its own, but it does have a position, and
         * one at the segment's base names the source it introduces rather than whatever sits at
         * offset zero.
         */
        @Test
        void sitsAtTheBaseOfTheSourceItNames() {
            BundleResult result = Bundler.bundle(List.of(source("a.css", "a{color:red}"), //
                                                         source("b.css", ".b{top:0}")),
                                                 BundleOptions.builder() //
                                                              .banners(true) //
                                                              .build());

            Node second = result.ast().children().stream().filter(Comment.class::isInstance).toList().get(1);
            assertThat(second.span()).isEqualTo(new SourceSpan("a{color:red}".length(), 0));
            assertThat(result.sourceIndex().resolve(second.span())).isEqualTo(new Origin("b.css", 0));
        }
    }

    @Nested
    class Diagnostics {

        @Test
        void mergesEverySourcesDiagnosticsWithGlobalSpans() {
            BundleResult result = Bundler.bundle(List.of(source("good.css", "a{color:red}"),
                                                         source("bad.css", ".b{background:rgb(0 0 0;}")));

            assertThat(result.hasErrors()).isTrue();

            for (Diagnostic diagnostic : result.diagnostics(Severity.ERROR)) {
                assertThat(result.sourceIndex().resolve(diagnostic.span()).sourceId()).isEqualTo("bad.css");
            }
        }

        /**
         * The charset warning has to name the source that declared it. Left at offset zero it
         * would have named the first source in every bundle, with a message naming an encoding,
         * so nothing in it would have looked wrong.
         */
        @Test
        void namesTheSourceWhoseCharsetCouldNotBeResolved() {
            BundleResult result = Bundler.bundle(List.of(source("first.css", "a{color:red}"),
                                                         source("second.css", "@charset \"nonsense\";.b{top:0}")));

            assertThat(result.diagnostics(Severity.WARNING)).singleElement().satisfies(warning -> {
                assertThat(warning.message()).contains("nonsense");
                assertThat(result.sourceIndex().resolve(warning.span()).sourceId()).isEqualTo("second.css");
            });
        }
    }

    /**
     * Prologue normalization means a bundle's output is not the concatenation of its inputs'
     * outputs, so idempotence is the only round-trip property available, the same reasoning
     * the serializer property already settled. Stated over recovered wreckage, since that is
     * the case where the first pass is allowed to normalize and no pass after it is.
     */
    @Test
    void isAFixedPointOnItsOwnOutput() {
        BundleResult bundled = Bundler.bundle(List.of(source("a.css", "@charset \"utf-8\";a{color:red}"),
                                                      source("b.css", "@import url(x);.b{background:rgb(0 0 0;}"),
                                                      source("c.css", "@media print{.c{top:0}}")));

        for (SerializerOptions options : List.of(SerializerOptions.builder().build(),
                                                 SerializerOptions.builder().formatting(Formatting.MINIFIED).build(),
                                                 SerializerOptions.builder().legacyCompatible().build())) {
            String once = CssSerializer.serialize(bundled.ast(), options);
            String twice = CssSerializer.serialize(CssParser.parse(once).ast(), options);

            assertThat(twice).isEqualTo(once);
        }
    }

    @Test
    void bundlesNothingIntoAnEmptyStylesheet() {
        BundleResult result = Bundler.bundle(List.of());

        assertThat(result.ast().children()).isEmpty();
        assertThat(result.ast().span()).isEqualTo(new SourceSpan(0, 0));
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.sourceIndex().segments()).isEmpty();
    }

    /**
     * An {@code @import} nested inside a rule is left alone; hoisting it needs its condition.
     */
    @Test
    void leavesANestedImportWhereItSits() {
        BundleResult result = Bundler.bundle(List.of(source("a.css", "a{color:red}"),
                                                     source("b.css", "@media print{@import url(x.css);}")));

        assertThat(result.ast().children()).noneMatch(AtRule.class::isInstance);
        assertThat(result.ast().children().get(0)).isInstanceOf(StyleRule.class);
        assertThat(result.diagnostics()).isEmpty();
    }
}
