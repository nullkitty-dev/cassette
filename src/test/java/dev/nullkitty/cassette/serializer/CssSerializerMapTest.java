package dev.nullkitty.cassette.serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.bundle.BundleOptions;
import dev.nullkitty.cassette.bundle.BundleResult;
import dev.nullkitty.cassette.bundle.Bundler;
import dev.nullkitty.cassette.bundle.Importer;
import dev.nullkitty.cassette.bundle.Source;
import dev.nullkitty.cassette.diagnostics.SourceResolver;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.sourcemap.MapDecoder;
import dev.nullkitty.cassette.sourcemap.SourceMap;

/**
 * A tree in, CSS and a map out.
 */
class CssSerializerMapTest {

    @Nested
    class OneSource {

        @Test
        void pointsEachConstructAtWhatAPersonWouldExpect() {
            String css = """
                .a, .b { color: red; }
                @media print { p { top: 0 } }
                """;

            assertThat(dump(css,
                            SerializerOptions.DEFAULTS)).containsExactly("0:0 -> in.css:0:0  «.a, .b { color: red; }»",
                                                                         "1:0 -> in.css:0:4  «.b { color: red; }»",
                                                                         "2:2 -> in.css:0:9  «color: red; }»",
                                                                         "5:0 -> in.css:1:0  «@media print { p { top: …»",
                                                                         "6:2 -> in.css:1:15  «p { top: 0 } }»",
                                                                         "7:4 -> in.css:1:19  «top: 0 } }»");
        }

        @Test
        void putsEverythingOnOneOutputLineWhenMinified() {
            String css = ".a { color: red }\n.b { top: 0 }\n";

            assertThat(dump(css, minified())).containsExactly("0:0 -> in.css:0:0  «.a { color: red }»",
                                                              "0:3 -> in.css:0:5  «color: red }»",
                                                              "0:13 -> in.css:1:0  «.b { top: 0 }»",
                                                              "0:16 -> in.css:1:5  «top: 0 }»");
        }

        @Test
        void countsOutputLinesThroughAMultiLineComment() {
            // The reason a mapping is an offset and not a line counter: the writer appends a
            // comment's text verbatim and does not know how many newlines went with it.
            String css = "/* one\ntwo\nthree */\n.a { top: 0 }\n";

            assertThat(dump(css, SerializerOptions.DEFAULTS)).containsExactly("3:0 -> in.css:3:0  «.a { top: 0 }»",
                                                                              "4:2 -> in.css:3:5  «top: 0 }»");
        }

        @Test
        void collectsTheSourceAndItsContent() {
            SerializeResult result = serialize(".a { top: 0 }", SerializerOptions.DEFAULTS);

            assertThat(result.sourceMap().sources()).containsExactly("in.css");
            assertThat(result.sourceMap().sourcesContent()).containsExactly(".a { top: 0 }");
        }

        @Test
        void mapsNothingInATreeWithNoSpans() {
            // A hand-built tree carries NONE, which is bit-identical to a zero-width span at
            // the start of the first source, so the length is the only test, and nothing is
            // claimed rather than something wrong.
            Stylesheet synthesized = new Stylesheet(List.of(), 0L);
            SerializeResult result = CssSerializer.serializeWithMap(synthesized,
                                                                    SerializerOptions.DEFAULTS,
                                                                    SourceResolver.of("in.css", ""));

            assertThat(result.css()).isEmpty();
            assertThat(result.sourceMap().mappings()).isEmpty();
            assertThat(result.sourceMap().sources()).isEmpty();
        }
    }

    @Nested
    class ByteIdentity {

        @Test
        void returnsExactlyWhatSerializeWouldHave() {
            // The constraint the whole feature is subordinate to, at the public entry point.
            String css = """
                @charset "utf-8";
                /* a comment */
                .a, .b > .c:is(.d, .e) { color: red; margin: 0 1px }
                @media (min-width: 30em) { p::before { content: "x"; } }
                @font-face { font-family: "X"; src: url("x.woff2") }
                @a \\;
                """;
            Stylesheet sheet = CssParser.parse(css).ast();
            SourceResolver resolver = SourceResolver.of("in.css", css);

            for (SerializerOptions options : optionSets()) {
                assertThat(CssSerializer.serializeWithMap(sheet, options, resolver)
                                        .css()).isEqualTo(CssSerializer.serialize(sheet, options));
            }
        }

        @Test
        void returnsExactlyWhatSerializeWouldHaveAfterOptimizing() {
            String css = ".a { color: #ff0000; margin: 0.50px }";
            Stylesheet sheet = Optimizer.optimize(CssParser.parse(css).ast(), Optimizations.all());
            SourceResolver resolver = SourceResolver.of("in.css", css);

            for (SerializerOptions options : optionSets()) {
                assertThat(CssSerializer.serializeWithMap(sheet, options, resolver)
                                        .css()).isEqualTo(CssSerializer.serialize(sheet, options));
            }
        }
    }

    @Nested
    class Bundles {

        private static final String ENTRY = "@import \"b.css\";\n.a { top: 0 }\n";

        private static final String B = "@import \"c.css\";\n.b { top: 1 }\n";

        private static final String C = ".c { top: 2 }\n";

        /**
         * Every source of the bundle, so a mapping can name any of them.
         */
        private static BundleResult bundled() {
            Map<String, String> files = Map.of("b.css", B, "c.css", C);
            Importer importer =
                (specifier, from) -> Optional.ofNullable(files.get(specifier))
                                             .map(text -> new Source(specifier, text.getBytes(StandardCharsets.UTF_8)));

            return Bundler.bundle(List.of(new Source("entry.css", ENTRY.getBytes(StandardCharsets.UTF_8))),
                                  BundleOptions.builder().importer(importer).build());
        }

        @Test
        void namesEveryFileAMappingLandsIn() {
            BundleResult bundle = bundled();
            SerializeResult result = CssSerializer.serializeWithMap(bundle.ast(), minified(), bundle.sourceIndex());

            assertThat(result.sourceMap().sources()).containsExactlyInAnyOrder("c.css", "b.css", "entry.css");

            assertThat(MapDecoder.dump(result.sourceMap())).containsExactly("0:0 -> c.css:0:0  «.c { top: 2 }»",
                                                                            "0:3 -> c.css:0:5  «top: 2 }»",
                                                                            "0:9 -> b.css:1:0  «.b { top: 1 }»",
                                                                            "0:12 -> b.css:1:5  «top: 1 }»",
                                                                            "0:18 -> entry.css:1:0  «.a { top: 0 }»",
                                                                            "0:21 -> entry.css:1:5  «top: 0 }»");
        }

        @Test
        void skipsAWrapperSpanningTwoSourcesAndKeepsEverythingUnderIt() {
            // A conditional import wraps the imported sheet, and that sheet imported another,
            // so the wrapper's span covers both and SourceIndex refuses it. The rule it wraps
            // still maps, which is the half that matters.
            Map<String, String> files =
                Map.of("b.css", "@import \"c.css\";\n.b { top: 1 }\n", "c.css", ".c { top: 2 }\n");

            Importer importer =
                (specifier, from) -> Optional.ofNullable(files.get(specifier))
                                             .map(text -> new Source(specifier, text.getBytes(StandardCharsets.UTF_8)));

            BundleResult bundle =
                Bundler.bundle(List.of(new Source("entry.css",
                                                  "@import \"b.css\" print;\n".getBytes(StandardCharsets.UTF_8))),
                               BundleOptions.builder().importer(importer).build());

            SerializeResult result = CssSerializer.serializeWithMap(bundle.ast(), minified(), bundle.sourceIndex());

            assertThat(result.css()).contains("@media print");

            assertThat(MapDecoder.dump(result.sourceMap())).noneMatch(line -> line.contains("@media print"))
                                                           .anyMatch(line -> line.contains("c.css"))
                                                           .anyMatch(line -> line.contains("b.css"));
        }
    }

    @Test
    void refusesNullArguments() {
        Stylesheet sheet = CssParser.parse("a{}").ast();
        SourceResolver resolver = SourceResolver.of("in.css", "a{}");

        assertThatThrownBy(() -> CssSerializer.serializeWithMap(null,
                                                                SerializerOptions.DEFAULTS,
                                                                resolver)).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> CssSerializer.serializeWithMap(sheet,
                                                                SerializerOptions.DEFAULTS,
                                                                null)).isInstanceOf(NullPointerException.class);
    }

    private static List<SerializerOptions> optionSets() {
        return List.of(SerializerOptions.DEFAULTS,
                       minified(),
                       SerializerOptions.builder().nesting(NestingMode.FLATTEN).formatting(Formatting.MINIFIED).build(),
                       SerializerOptions.builder().legacyCompatible().build());
    }

    private static SerializerOptions minified() {
        return SerializerOptions.builder().formatting(Formatting.MINIFIED).build();
    }

    private static SerializeResult serialize(String css, SerializerOptions options) {
        return CssSerializer.serializeWithMap(CssParser.parse(css).ast(), options, SourceResolver.of("in.css", css));
    }

    private static List<String> dump(String css, SerializerOptions options) {
        SourceMap map = serialize(css, options).sourceMap();
        return MapDecoder.dump(map);
    }
}
