package dev.nullkitty.cassette.serializer;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import dev.nullkitty.cassette.bundle.BundleFixtures;
import dev.nullkitty.cassette.bundle.BundleOptions;
import dev.nullkitty.cassette.bundle.BundleResult;
import dev.nullkitty.cassette.diagnostics.SourceResolver;
import dev.nullkitty.cassette.fixtures.Fixture;
import dev.nullkitty.cassette.fixtures.FixtureLoader;
import dev.nullkitty.cassette.fixtures.Golden;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.sourcemap.MapDecoder;

/**
 * Golden source maps, decoded rather than raw.
 *
 * <p>A {@code mappings} string is an unreadable diff, and in this project the diff <em>is</em>
 * the review artifact, so a {@code .map.txt} variant holds one line per mapping saying where
 * in the output it sits, which file it names and what that file says there. Regenerating one
 * and reading the diff tells you what moved; regenerating a VLQ string tells you nothing.
 *
 * <p>Exactly one fixture also carries a {@code .map.json}, which pins the encoder and the JSON
 * writer themselves. One is enough: every other case is checked through the decoder, which is
 * written against the format rather than against the encoder.
 */
class SourceMapFixtureTest {

    private static final String DUMP_SUFFIX = ".map.txt";

    private static final String JSON_SUFFIX = ".map.json";

    @TestFactory
    List<DynamicTest> matchesGoldenMaps() {
        return FixtureLoader.loadAll().stream()
                            .flatMap(fixture -> fixture.variants().stream()
                                                       .filter(variant -> variant.endsWith(DUMP_SUFFIX)
                                                                          || variant.endsWith(JSON_SUFFIX))
                                                       .map(variant -> dynamicTest(fixture.name() + " " + variant,
                                                                                   () -> assertMap(fixture, variant))))
                            .toList();
    }

    private static void assertMap(Fixture fixture, String variant) {
        boolean json = variant.endsWith(JSON_SUFFIX);
        SerializerOptions options = SerializerFixtureTest.optionsFor(variant, json ? JSON_SUFFIX : DUMP_SUFFIX);
        SerializeResult result = serialize(fixture, options);
        Golden.assertMatches(fixture,
                             variant,
                             json ? result.sourceMap().toJson()
                                  : String.join("\n", MapDecoder.dump(result.sourceMap())));
    }

    /**
     * A bundle fixture differs in one thing, which {@link SourceResolver} the call takes.
     * Everything else about it is identical.
     */
    private static SerializeResult serialize(Fixture fixture, SerializerOptions options) {
        if (fixture.isBundle()) {
            BundleResult bundle = BundleFixtures.bundle(fixture, BundleOptions.DEFAULTS);
            return CssSerializer.serializeWithMap(bundle.ast(), options, bundle.sourceIndex());
        }
        // The decoded text, not the bytes: a span indexes what §3.3 preprocessing produced, and
        // a BOM or a CRLF makes the two different lengths.
        String text = CssParser.decode(fixture.input());
        return CssSerializer.serializeWithMap(CssParser.parse(text).ast(),
                                              options,
                                              SourceResolver.of("input.css", text));
    }
}
