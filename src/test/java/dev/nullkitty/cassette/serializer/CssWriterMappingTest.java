package dev.nullkitty.cassette.serializer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.parser.CssParser;

/**
 * Where the writer records a mapping, and what that costs the output.
 *
 * <p>No public entry point builds a map yet; the encoder and {@code serializeWithMap} are a
 * later step, so everything here drives {@link CssWriter} directly, which is also the only
 * level at which the byte-identity constraint can be stated as a test: the same writer, the
 * same tree, the same options, with the mapping list present and absent.
 */
class CssWriterMappingTest {

    @Nested
    class RecordingSites {

        @Test
        void recordsEachAlternativeOfARulePrelude() {
            assertThat(map(".a,.b{top:0}", SerializerOptions.DEFAULTS)).containsExactly("0:0 -> .a",
                                                                                        "1:0 -> .b",
                                                                                        "2:2 -> top:0");
        }

        @Test
        void recordsADeclarationAtItsPropertyAndNotAtItsIndent() {
            assertThat(map("a{color:red}", SerializerOptions.DEFAULTS)).containsExactly("0:0 -> a", "1:2 -> color:red");
        }

        @Test
        void recordsAnAtRuleAtItsSign() {
            assertThat(map("@import url(\"a.css\");",
                           SerializerOptions.DEFAULTS)).containsExactly("0:0 -> @import url(\"a.css\");");
        }

        @Test
        void recordsAConditionalGroupRuleAndEverythingInsideIt() {
            assertThat(map("@media print{p{top:0}}",
                           SerializerOptions.DEFAULTS)).containsExactly("0:0 -> @media print{p{top:0}}",
                                                                        "1:2 -> p",
                                                                        "2:4 -> top:0");
        }

        @Test
        void doesNotRecordASelectorInsideAFunctionalPseudoClass() {
            // Finer than a rule prelude, which is the granularity browser devtools consume.
            // ':is(.a, .b)' is one prelude alternative and gets one mapping, not three.
            assertThat(map(":is(.a,.b) p{top:0}", SerializerOptions.DEFAULTS)).containsExactly("0:0 -> :is(.a,.b) p",
                                                                                               "1:2 -> top:0");
        }

        @Test
        void doesNotRecordComponentValuesOrComments() {
            assertThat(map("a{/*x*/top:calc(1px + 2px)}",
                           SerializerOptions.DEFAULTS)).containsExactly("0:0 -> a", "2:2 -> top:calc(1px + 2px)");
        }

        @Test
        void recordsTheSamePlacesWhenMinified() {
            assertThat(map(".a,.b{top:0}@media print{p{q:1}}",
                           minified())).containsExactly("0:0 -> .a",
                                                        "0:3 -> .b",
                                                        "0:6 -> top:0",
                                                        "0:12 -> @media print{p{q:1}}",
                                                        "0:25 -> p",
                                                        "0:27 -> q:1");
        }
    }

    @Nested
    class Invariants {

        @Test
        void recordsOffsetsInNonDecreasingOrder() {
            Mappings mappings = record(parse(BUSY), SerializerOptions.DEFAULTS);

            assertThat(mappings.size()).isPositive();

            for (int index = 1; index < mappings.size(); index++) {
                assertThat(mappings.outputOffset(index)).isGreaterThanOrEqualTo(mappings.outputOffset(index - 1));
            }
        }

        @Test
        void recordsNoOffsetPastTheEndOfTheOutput() {
            for (SerializerOptions options : optionSets()) {
                Stylesheet sheet = parse(BUSY);
                Mappings mappings = new Mappings(SourceSpan.lengthOf(sheet.packedSpan()));
                String css = new CssWriter(options, Diagnostic.DISCARD, mappings).write(sheet);

                for (int index = 0; index < mappings.size(); index++) {
                    assertThat(mappings.outputOffset(index)).isLessThan(css.length());
                }
            }
        }

        @Test
        void recordsNoSynthesizedSpan() {
            Mappings mappings = record(parse(BUSY), SerializerOptions.DEFAULTS);

            for (int index = 0; index < mappings.size(); index++) {
                assertThat(SourceSpan.lengthOf(mappings.packedSpan(index))).isPositive();
            }
        }
    }

    /**
     * That the arrays are sized once, which is the claim the design put on the benchmark and
     * which is really a question about the estimate rather than about cost.
     *
     * <p>Same shape as {@code TokenBufferTest.Sizing}, and asserted the same way round: that a
     * sample denser than real CSS still does not grow them. Comparing the count against the
     * capacity after the fact cannot fail, because growth raises the second above the first by
     * construction.
     */
    @Nested
    class Sizing {

        /**
         * Nested authored CSS, which is the shape a maps-on build feeds in and the densest the
         * corpus measurement found, 0.042 mappings per source character against 0.027–0.031 for
         * the compiled, flat entries. A nested rule's prelude is {@code &:hover} where the flat
         * rule it compiles to repeats the whole ancestor chain, so nesting raises the mapping
         * count and lowers the character count at once.
         */
        private static final String NESTED = """
            .card {
              color: #fff;
              &:hover { top: 0 }
              &:focus { top: 1 }
              .title { top: 2 }
              .body { top: 3; a { top: 4 } }
              @media print { top: 5 }
            }
            .grid { display: grid; > .card { top: 6 } }
            """;

        @Test
        void doesNotGrowForNestedAuthoredCss() {
            Stylesheet sheet = parse(NESTED);
            Mappings mappings = new Mappings(SourceSpan.lengthOf(sheet.packedSpan()));

            new CssWriter(SerializerOptions.DEFAULTS, Diagnostic.DISCARD, mappings).write(sheet);

            assertThat(mappings.capacity()).as("the mapping arrays grew, so 0.05 per source character is too tight")
                                           .isEqualTo(Mappings.estimateCapacity(SourceSpan.lengthOf(sheet.packedSpan())));
        }

        @Test
        void theSampleAboveIsDenserThanRealStylesheets() {
            Stylesheet sheet = parse(NESTED);
            int length = SourceSpan.lengthOf(sheet.packedSpan());
            Mappings mappings = new Mappings(length);

            new CssWriter(SerializerOptions.DEFAULTS, Diagnostic.DISCARD, mappings).write(sheet);
            double perCharacter = (double) mappings.size() / length;

            // What makes the test above worth anything. The corpus runs 0.027 to 0.031 and the
            // nested sample the estimate was chosen against ran 0.042; this is at least that
            // dense, so it stands in for the worst real shape rather than for an average one.
            assertThat(perCharacter).isGreaterThan(0.042);
        }

        @Test
        void growsRatherThanFailingWhenTheEstimateIsWrong() {
            // The estimate is a bet, not an invariant, so the fallback has to work: a tree whose
            // own span reports nothing takes the floor and grows from there.
            Stylesheet sheet = parse(NESTED);
            Mappings mappings = new Mappings(0);

            new CssWriter(SerializerOptions.DEFAULTS, Diagnostic.DISCARD, mappings).write(sheet);

            assertThat(mappings.size()).isPositive();
            assertThat(mappings.capacity()).isGreaterThanOrEqualTo(mappings.size());
        }
    }

    @Nested
    class ByteIdentity {

        @Test
        void writesTheSameCssWithMappingsOnAsOff() {
            // The constraint the whole feature is subordinate to: a development build that
            // emits a map and a production build that does not must ship the same stylesheet.
            for (SerializerOptions options : optionSets()) {
                Stylesheet sheet = parse(BUSY);
                String without = new CssWriter(options, Diagnostic.DISCARD).write(sheet);
                String with = new CssWriter(options,
                                            Diagnostic.DISCARD,
                                            new Mappings(SourceSpan.lengthOf(sheet.packedSpan()))).write(sheet);

                assertThat(with).isEqualTo(without);
            }
        }

        @Test
        void writesTheSameCssThroughEveryRollbackPath() {
            // Every construct the writer commits output for and then takes back: an at-rule
            // whose prelude writes nothing, a block that writes nothing, a declaration value
            // that writes nothing, and the trailing ';' minification drops.
            String wreckage = "@a \\;@b{\\}c{d:\\}e{f:1;}@font-face{src:url(x \")}";

            for (SerializerOptions options : optionSets()) {
                Stylesheet sheet = parse(wreckage);
                String without = new CssWriter(options, Diagnostic.DISCARD).write(sheet);
                String with = new CssWriter(options,
                                            Diagnostic.DISCARD,
                                            new Mappings(SourceSpan.lengthOf(sheet.packedSpan()))).write(sheet);

                assertThat(with).isEqualTo(without);
            }
        }
    }

    private static final String BUSY = """
        @charset "utf-8";
        /* a comment */
        .a, .b > .c:is(.d, .e) { color: red; margin: 0 1px }
        @media (min-width: 30em) and print {
          p::before { content: "x"; }
          @supports (display: grid) { .g { display: grid } }
        }
        @font-face { font-family: "X"; src: url("x.woff2") }
        @a \\;
        """;

    private static List<SerializerOptions> optionSets() {
        return List.of(SerializerOptions.DEFAULTS,
                       minified(),
                       SerializerOptions.builder().nesting(NestingMode.FLATTEN).formatting(Formatting.MINIFIED).build(),
                       SerializerOptions.builder().legacyCompatible().build());
    }

    private static SerializerOptions minified() {
        return SerializerOptions.builder().formatting(Formatting.MINIFIED).build();
    }

    private static Stylesheet parse(String css) {
        return CssParser.parse(css).ast();
    }

    private static Mappings record(Stylesheet sheet, SerializerOptions options) {
        Mappings mappings = new Mappings(SourceSpan.lengthOf(sheet.packedSpan()));
        new CssWriter(options, Diagnostic.DISCARD, mappings).write(sheet);
        return mappings;
    }

    /**
     * Every mapping as {@code outLine:outColumn -> the source text it points at}, which is the
     * shape the golden fixtures will take and is readable in a way a VLQ string is not.
     */
    private static List<String> map(String css, SerializerOptions options) {
        Stylesheet sheet = parse(css);
        Mappings mappings = new Mappings(SourceSpan.lengthOf(sheet.packedSpan()));
        String out = new CssWriter(options, Diagnostic.DISCARD, mappings).write(sheet);

        List<String> lines = new ArrayList<>();

        for (int index = 0; index < mappings.size(); index++) {
            int offset = mappings.outputOffset(index);
            int line = 0;
            int lineStart = 0;

            for (int at = 0; at < offset; at++) {
                if (out.charAt(at) == '\n') {
                    line++;
                    lineStart = at + 1;
                }
            }

            long span = mappings.packedSpan(index);
            int start = SourceSpan.startOf(span);
            lines.add(line
                      + ":"
                      + (offset - lineStart)
                      + " -> "
                      + css.substring(start, start + SourceSpan.lengthOf(span)));
        }

        return lines;
    }
}
