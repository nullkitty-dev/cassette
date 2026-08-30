package dev.nullkitty.cassette.sourcemap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The format, on its own.
 *
 * <p>Everything encoded here is decoded again by {@link MapDecoder} and compared with what
 * went in. A hand-written expected VLQ string asserts that the encoder still does what it did,
 * which is a weaker claim than that it does what the format says, and the format is the thing
 * a browser will hold it to.
 */
class SourceMapTest {

    @Nested
    class Encoding {

        @Test
        void writesOneSegmentPerMapping() {
            SourceMap map = SourceMap.builder() //
                                     .mapping(0, 0, 0, 0, 0) //
                                     .mapping(0, 5, 0, 0, 4) //
                                     .build();

            assertThat(map.mappings()).isEqualTo("AAAA,KAAI");
            assertThat(MapDecoder.segments(map.mappings())).containsExactly("0:0 -> 0:0:0", "0:5 -> 0:0:4");
        }

        @Test
        void separatesOutputLinesWithSemicolons() {
            SourceMap map = SourceMap.builder() //
                                     .mapping(0, 0, 0, 0, 0) //
                                     .mapping(1, 2, 0, 1, 0) //
                                     .build();

            assertThat(map.mappings()).isEqualTo("AAAA;EACA");
            assertThat(MapDecoder.segments(map.mappings())).containsExactly("0:0 -> 0:0:0", "1:2 -> 0:1:0");
        }

        @Test
        void writesAnEmptySemicolonForAnOutputLineWithNoMappings() {
            SourceMap map = SourceMap.builder() //
                                     .mapping(0, 0, 0, 0, 0) //
                                     .mapping(3, 0, 0, 9, 0) //
                                     .build();

            assertThat(map.mappings()).isEqualTo("AAAA;;;AASA");
            assertThat(MapDecoder.segments(map.mappings())).containsExactly("0:0 -> 0:0:0", "3:0 -> 0:9:0");
        }

        @Test
        void resetsTheGeneratedColumnAtEachLineButNothingElse() {
            // The generated column is the one field that is absolute within its line; the
            // other three accumulate across the whole map.
            SourceMap map = SourceMap.builder() //
                                     .mapping(0, 40, 0, 0, 0) //
                                     .mapping(1, 2, 0, 1, 3) //
                                     .build();

            assertThat(MapDecoder.segments(map.mappings())).containsExactly("0:40 -> 0:0:0", "1:2 -> 0:1:3");
        }

        @Test
        void encodesNegativeDeltas() {
            SourceMap map = SourceMap.builder() //
                                     .mapping(0, 0, 0, 8, 20) //
                                     .mapping(0, 4, 0, 2, 1) //
                                     .build();

            assertThat(MapDecoder.segments(map.mappings())).containsExactly("0:0 -> 0:8:20", "0:4 -> 0:2:1");
        }

        @Test
        void encodesValuesPastOneVlqDigit() {
            // Anything over 15 needs a continuation group, and a large line number needs three.
            SourceMap map = SourceMap.builder() //
                                     .mapping(0, 0, 0, 0, 0) //
                                     .mapping(0, 1000, 1, 65432, 4096) //
                                     .build();

            assertThat(MapDecoder.segments(map.mappings())).containsExactly("0:0 -> 0:0:0", "0:1000 -> 1:65432:4096");
        }

        @Test
        void refusesAMappingOnAnEarlierOutputLine() {
            SourceMap.Builder builder = SourceMap.builder().mapping(4, 0, 0, 0, 0);

            assertThatThrownBy(() -> builder.mapping(3, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class)
                                                                    .hasMessageContaining("delta-encoded");
        }

        @Test
        void encodesNothingForNoMappings() {
            assertThat(SourceMap.builder().build().mappings()).isEmpty();
        }
    }

    @Nested
    class Sources {

        @Test
        void internsBySourceIdInFirstSeenOrder() {
            SourceMap.Builder builder = SourceMap.builder();

            assertThat(builder.source("b.css", "bbb")).isZero();
            assertThat(builder.source("a.css", "aaa")).isEqualTo(1);
            assertThat(builder.source("b.css", "bbb")).isZero();

            SourceMap map = builder.build();
            assertThat(map.sources()).containsExactly("b.css", "a.css");
            assertThat(map.sourcesContent()).containsExactly("bbb", "aaa");
        }

        @Test
        void refusesContentThatDoesNotMatchTheSources() {
            assertThatThrownBy(() -> new SourceMap(null,
                                                   null,
                                                   List.of("a.css", "b.css"),
                                                   List.of("aaa"),
                                                   "")).isInstanceOf(IllegalArgumentException.class)
                                                       .hasMessageContaining("indexed by the same index");
        }

        @Test
        void allowsContentToBeOmittedEntirely() {
            SourceMap map = new SourceMap(null, null, List.of("a.css"), null, "AAAA");

            assertThat(map.sourcesContent()).isNull();
            assertThat(map.toJson()).doesNotContain("sourcesContent");
        }
    }

    @Nested
    class Serialization {

        @Test
        void writesTheMembersTheSpecificationLists() {
            SourceMap map = new SourceMap("out.css", "/src", List.of("a.css"), List.of("a{}"), "AAAA");

            assertThat(map.toJson()).isEqualTo("{\"version\":3,\"file\":\"out.css\","
                                               + "\"sourceRoot\":\"/src\",\"sources\":[\"a.css\"],"
                                               + "\"sourcesContent\":[\"a{}\"],\"names\":[],\"mappings\":\"AAAA\"}");
        }

        @Test
        void omitsFileAndSourceRootWhenUnset() {
            SourceMap map = new SourceMap(null, null, List.of("a.css"), List.of("a{}"), "AAAA");

            assertThat(map.toJson()).isEqualTo("{\"version\":3,\"sources\":[\"a.css\"],"
                                               + "\"sourcesContent\":[\"a{}\"],\"names\":[],\"mappings\":\"AAAA\"}");
        }

        @Test
        void writesNamesAsAnEmptyArrayRatherThanOmittingIt() {
            // So nobody reads an absent array as an unfinished one. CSS renames nothing.
            assertThat(new SourceMap(null, null, List.of(), List.of(), "").toJson()).contains("\"names\":[]");
        }

        @Test
        void escapesWhatJsonRequires() {
            SourceMap map = new SourceMap(null, null, List.of("a\"b\\c.css"), List.of("a{content:\"\t\"}\n"), "AAAA");

            assertThat(map.toJson()).contains("\"a\\\"b\\\\c.css\"").contains("a{content:\\\"\\t\\\"}\\n");
        }

        @Test
        void escapesTheTwoSeparatorsThatWouldBreakAnInlinedMap() {
            // Legal JSON unescaped, and a statement terminator in the JavaScript context a
            // data: URI puts them in.
            String separators = "a{}" + (char) 0x2028 + "b{}" + (char) 0x2029;
            SourceMap map = new SourceMap(null, null, List.of("a.css"), List.of(separators), "AAAA");

            assertThat(map.toJson()).contains("\\u2028").contains("\\u2029").doesNotContain(separators);
        }

        @Test
        void escapesControlCharacters() {
            SourceMap map = new SourceMap(null,
                                          null,
                                          List.of("a.css"),
                                          List.of("a" + (char) 0x00 + "b" + (char) 0x1f + "c"),
                                          "AAAA");

            assertThat(map.toJson()).contains("a\\u0000b\\u001fc");
        }
    }

    /**
     * That {@code toJson}'s buffer is sized over rather than under.
     *
     * <p>The first version was short by 3.7% to 5.6% on every corpus entry, which doubles a
     * multi-megabyte builder and copies everything written so far, the token buffer's mistake in
     * a second place. Asserted as "the estimate covers the output" against content carrying the
     * characters that expand, because that is the direction that can fail.
     */
    @Nested
    class Sizing {

        /**
         * Formatted CSS, and enough of it to matter.
         *
         * <p>Big enough to be able to fail. The fixed overhead absorbs a few hundred characters of
         * expansion, so a ten-line sample passes whether or not the headroom is there and proves
         * nothing, which is what a first version of this test did. Five hundred rules put the
         * expansion well past that.
         */
        private static String formatted(int rules) {
            StringBuilder css = new StringBuilder();
            for (int i = 0; i < rules; i++) {
                css.append(".r").append(i).append(" {\n  content: \"x\";\n\tcolor: red;\n}\n");
            }

            return css.toString();
        }

        @Test
        void theEstimateCoversFormattedCss() {
            SourceMap map =
                new SourceMap("out.css", null, List.of("in.css"), List.of(formatted(500)), "AAAA,KAAI;EACA");

            assertThat(map.estimateLength()).as("the buffer would grow, so the escape headroom is too tight")
                                            .isGreaterThanOrEqualTo(map.toJson().length());
        }

        @Test
        void theSampleAboveEscapesMoreThanRealCssDoes() {
            // What makes the test above worth anything, and the pair TokenBufferTest.Sizing
            // holds for the same reason. Newlines alone are 4-5% of formatted CSS; this sample
            // adds a tab and two quotes to every rule and reaches 19%, so it stands in for the
            // dense end of plausible rather than for an average.
            String css = formatted(500);
            SourceMap map = new SourceMap(null, null, List.of("in.css"), List.of(css), "AAAA");
            int escaped = map.toJson().length() - map.toJson().replace("\\", "").length();

            assertThat((double) escaped / css.length()).isGreaterThan(0.15);
        }

        /**
         * The estimate is a bet and not a bound, and this is the shape that outruns it: content
         * that is <em>entirely</em> newlines doubles, where a tenth of headroom covers a tenth.
         * What must hold is that the fallback works, a growth, which is what the unsized version
         * did on every call, so the output is right and only the buffer paid.
         */
        @Test
        void growsRatherThanTruncatingWhenTheBetIsWrong() {
            String newlines = "\n".repeat(400);
            SourceMap map = new SourceMap(null, null, List.of("in.css"), List.of(newlines), "AAAA");

            assertThat(map.estimateLength()).isLessThan(map.toJson().length());

            // Every newline survived the growth, in order, and the members after it did too.
            assertThat(map.toJson()).contains("\"sourcesContent\":[\"" + "\\n".repeat(400) + "\"]")
                                    .endsWith("\"mappings\":\"AAAA\"}");
        }

        @Test
        void theEstimateCoversAMapWithNoContent() {
            SourceMap map = new SourceMap("out.css", "/src", List.of("a.css", "b.css"), null, "AAAA;EACA");

            assertThat(map.estimateLength()).isGreaterThanOrEqualTo(map.toJson().length());
        }
    }

    /**
     * {@code writeJson} against {@code toJson}, which must never disagree.
     *
     * <p>{@code toJson} delegates, so they cannot drift while it does. The test is on the
     * delegation, which a later change to one path could remove.
     */
    @Nested
    class Streaming {

        @Test
        void writesWhatToJsonWouldHaveBuilt() throws IOException {
            SourceMap map = new SourceMap("out.css",
                                          "/src",
                                          List.of("a.css", "b.css"),
                                          List.of("a{}\n", "b{content:\"x\"}\n"),
                                          "AAAA,KAAI;EACA");

            StringWriter out = new StringWriter();
            map.writeJson(out);

            assertThat(out.toString()).isEqualTo(map.toJson());
        }

        @Test
        void agreesOnAMapWithEveryMemberOmitted() {
            SourceMap map = new SourceMap(null, null, List.of(), null, "");

            assertThat(written(map)).isEqualTo(map.toJson())
                                    .isEqualTo("{\"version\":3,\"sources\":[],\"names\":[],\"mappings\":\"\"}");
        }

        @Test
        void agreesOnContentNeedingEveryEscape() {
            String awkward = "a{}\n\t\"\\" + (char) 0x01 + (char) 0x2028;
            SourceMap map = new SourceMap(null, null, List.of("in.css"), List.of(awkward), "AAAA");

            assertThat(written(map)).isEqualTo(map.toJson());
        }

        @Test
        void reportsWhatTheDestinationReports() {
            SourceMap map = new SourceMap(null, null, List.of("a.css"), List.of("a{}"), "AAAA");

            assertThatThrownBy(() -> map.writeJson(new Appendable() {

                @Override
                public Appendable append(CharSequence text) throws IOException {
                    throw new IOException("full");
                }

                @Override
                public Appendable append(CharSequence text, int start, int end) throws IOException {
                    throw new IOException("full");
                }

                @Override
                public Appendable append(char c) throws IOException {
                    throw new IOException("full");
                }
            })).isInstanceOf(IOException.class).hasMessage("full");
        }

        @Test
        void refusesANullDestination() {
            SourceMap map = new SourceMap(null, null, List.of(), null, "");

            assertThatThrownBy(() -> map.writeJson(null)).isInstanceOf(NullPointerException.class);
        }

        private static String written(SourceMap map) {
            StringWriter out = new StringWriter();
            try {
                map.writeJson(out);
            }
            catch (IOException impossible) {
                throw new AssertionError(impossible);
            }

            return out.toString();
        }
    }

    @Nested
    class Trailer {

        @Test
        void recognizesWhatItWrites() {
            String comment = SourceMap.trailerFor("a.css.map");

            // Comment.text is the body, so strip the delimiters the parser would have.
            String body = comment.substring(2, comment.length() - 2);

            assertThat(SourceMap.isTrailer(body)).isTrue();
        }

        @Test
        void acceptsTheOlderAtMarker() {
            assertThat(SourceMap.isTrailer("@ sourceMappingURL=a.map ")).isTrue();
        }

        @Test
        void acceptsLeadingWhitespaceAroundTheMarker() {
            assertThat(SourceMap.isTrailer("  #   sourceMappingURL=a.map")).isTrue();
        }

        @Test
        void rejectsAComentWithNoMarker() {
            assertThat(SourceMap.isTrailer(" sourceMappingURL=a.map ")).isFalse();
        }

        @Test
        void rejectsADifferentSpelling() {
            // Case-sensitive, because a tool honouring the annotation is; warning about or
            // removing a comment nothing acts on would be a false alarm about a real file.
            assertThat(SourceMap.isTrailer("# sourcemappingurl=a.map")).isFalse();
            assertThat(SourceMap.isTrailer("# sourceURL=a.map")).isFalse();
        }

        @Test
        void rejectsATruncatedName() {
            assertThat(SourceMap.isTrailer("# sourceMapping")).isFalse();
            assertThat(SourceMap.isTrailer("#")).isFalse();
            assertThat(SourceMap.isTrailer("")).isFalse();
        }
    }

    @Test
    void spellsTheTrailer() {
        assertThat(SourceMap.trailerFor("out.css.map")).isEqualTo("/*# sourceMappingURL=out.css.map */");
    }
}
