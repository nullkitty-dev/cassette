package dev.nullkitty.cassette.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.diagnostics.LineIndex;
import dev.nullkitty.cassette.diagnostics.SourceResolver;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.serializer.CssSerializer;
import dev.nullkitty.cassette.serializer.Formatting;
import dev.nullkitty.cassette.serializer.NestingMode;
import dev.nullkitty.cassette.serializer.Optimizations;
import dev.nullkitty.cassette.serializer.Optimizer;
import dev.nullkitty.cassette.serializer.SerializeResult;
import dev.nullkitty.cassette.serializer.SerializerOptions;
import dev.nullkitty.cassette.sourcemap.MapDecoder;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/**
 * What a source map must be true of, for anything the parser can produce.
 *
 * <p>The generators draw wreckage, and the seam fragments among them are aimed at the writer paths
 * that commit output and then take it back. That is what makes these the right place to state the
 * byte-identity constraint rather than a fixture.
 */
class SourceMapPropertiesTest {

    private static final SerializerOptions PRETTY = SerializerOptions.DEFAULTS;

    private static final SerializerOptions MINIFIED =
        SerializerOptions.builder().formatting(Formatting.MINIFIED).build();

    private static final SerializerOptions FLATTENED =
        SerializerOptions.builder().nesting(NestingMode.FLATTEN).formatting(Formatting.MINIFIED).build();

    private static final SerializerOptions LEGACY = SerializerOptions.builder().legacyCompatible().build();

    private static final List<SerializerOptions> ALL = List.of(PRETTY, MINIFIED, FLATTENED, LEGACY);

    /**
     * The constraint everything else is subordinate to: a development build that emits a map
     * and a production build that does not must ship the same stylesheet, byte for byte.
     */
    @Property
    void mapsOnWritesTheSameCssAsMapsOff(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        Stylesheet ast = CssParser.parse(input).ast();
        SourceResolver sources = SourceResolver.of("in.css", input);

        for (SerializerOptions options : ALL) {
            assertThat(CssSerializer.serializeWithMap(ast, options, sources)
                                    .css()).isEqualTo(CssSerializer.serialize(ast, options));
        }
    }

    /**
     * The same, through the transform pass, which rebuilds nodes and carries spans across.
     */
    @Property
    void mapsOnWritesTheSameCssAsMapsOffAfterOptimizing(@ForAll(
        supplier = CssLikeArbitraries.Text.class) String input) {
        Stylesheet ast = Optimizer.optimize(CssParser.parse(input).ast(), Optimizations.all());
        SourceResolver sources = SourceResolver.of("in.css", input);

        for (SerializerOptions options : ALL) {
            assertThat(CssSerializer.serializeWithMap(ast, options, sources)
                                    .css()).isEqualTo(CssSerializer.serialize(ast, options));
        }
    }

    @Property
    void neverThrows(@ForAll(supplier = CssLikeArbitraries.Bytes.class) byte[] input) {
        String text = CssParser.decode(input);
        Stylesheet ast = CssParser.parse(text).ast();
        SourceResolver sources = SourceResolver.of("in.css", text);

        for (SerializerOptions options : ALL) {
            assertThat(CssSerializer.serializeWithMap(ast, options, sources).sourceMap()).isNotNull();
        }
    }

    /**
     * Generated positions strictly increase, which is what lets the post-pass be one merge
     * walk over the output rather than a search per mapping.
     */
    @Property
    void generatedPositionsAdvance(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        for (SerializerOptions options : ALL) {
            int previousLine = -1;
            int previousColumn = -1;

            for (int[] at : positions(input, options)) {
                assertThat(at[0] > previousLine
                           || (at[0] == previousLine && at[1] > previousColumn))
                                                                                .as("%d:%d after %d:%d",
                                                                                    at[0],
                                                                                    at[1],
                                                                                    previousLine,
                                                                                    previousColumn)
                                                                                .isTrue();

                previousLine = at[0];
                previousColumn = at[1];
            }
        }
    }

    /**
     * Every generated position exists in the output, and never lands on whitespace.
     *
     * <p>The sharp one. A mapping points at the first character the writer emitted for a node,
     * and every one of those is a {@code @}, an identifier or a selector's punctuation, so a
     * position on a space or a newline means an indent or a separator was counted into the
     * construct that follows it, or a rollback left the mapping behind.
     */
    @Property
    void everyGeneratedPositionIsAtACharacterTheWriterEmitted(@ForAll(
        supplier = CssLikeArbitraries.Text.class) String input) {
        for (SerializerOptions options : ALL) {
            SerializeResult result = serialize(input, options);
            LineIndex lines = new LineIndex(result.css());

            for (int[] at : positions(result)) {
                int offset = offsetOf(result.css(), at[0], at[1]);

                assertThat(offset).as("%d:%d in %s", at[0], at[1], result.css()).isBetween(0,
                                                                                           result.css().length() - 1);

                assertThat(lines.lineOf(offset)).isEqualTo(at[0]);
                assertThat(lines.columnOf(offset)).isEqualTo(at[1]);

                assertThat(Character.isWhitespace(result.css()
                                                        .charAt(offset))).as("mapping at %d:%d lands on whitespace",
                                                                             at[0],
                                                                             at[1])
                                                                         .isFalse();
            }
        }
    }

    /**
     * Every source position exists in the source the mapping names.
     */
    @Property
    void everySourcePositionIsInsideTheSourceItNames(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        for (SerializerOptions options : ALL) {
            SerializeResult result = serialize(input, options);
            List<String> contents = result.sourceMap().sourcesContent();

            for (String segment : MapDecoder.segments(result.sourceMap().mappings())) {
                String[] where = segment.split(" -> ")[1].split(":");
                String content = contents.get(Integer.parseInt(where[0]));

                int line = Integer.parseInt(where[1]);
                int column = Integer.parseInt(where[2]);

                assertThat(offsetOf(content, line, column)).as("%s in source of length %d", segment, content.length())
                                                           .isBetween(0, content.length());
            }
        }
    }

    private static SerializeResult serialize(String input, SerializerOptions options) {
        return CssSerializer.serializeWithMap(CssParser.parse(input).ast(),
                                              options,
                                              SourceResolver.of("in.css", input));
    }

    private static List<int[]> positions(String input, SerializerOptions options) {
        return positions(serialize(input, options));
    }

    /**
     * The generated line and column of each mapping, in order.
     */
    private static List<int[]> positions(SerializeResult result) {
        return MapDecoder.segments(result.sourceMap().mappings()) //
                         .stream() //
                         .map(segment -> segment.split(" -> ")[0].split(":")) //
                         .map(at -> new int[] { Integer.parseInt(at[0]), Integer.parseInt(at[1]) }) //
                         .toList();
    }

    /**
     * A line and column back to an offset, or -1 if that position does not exist.
     *
     * <p>Counted forwards rather than through a {@code LineIndex}, so the property does not
     * check the index against itself.
     */
    private static int offsetOf(String text, int line, int column) {
        int at = 0;
        for (int seen = 0; seen < line; seen++) {
            int next = text.indexOf('\n', at);
            if (next < 0) {
                return -1;
            }

            at = next + 1;
        }

        int end = text.indexOf('\n', at);
        int lineLength = (end < 0 ? text.length() : end) - at;

        return column > lineLength ? -1 : at + column;
    }
}
