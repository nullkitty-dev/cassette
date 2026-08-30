package dev.nullkitty.cassette.jmh;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.serializer.CssSerializer;
import dev.nullkitty.cassette.serializer.Formatting;
import dev.nullkitty.cassette.serializer.NodeTransform;
import dev.nullkitty.cassette.serializer.Optimizations;
import dev.nullkitty.cassette.serializer.Optimizer;
import dev.nullkitty.cassette.serializer.SerializerOptions;

/**
 * How much smaller a real stylesheet gets, per transform, raw and gzipped.
 *
 * <p>A size report rather than a benchmark. It makes no timing claim and belongs beside
 * {@link MemoryCensus} rather than in the JMH suite. It exists because "how well does it minify" is
 * the first question asked of a CSS tool.
 *
 * <p>Gzipped as well as raw, and the gzipped column is the one to quote. Whitespace removal looks
 * spectacular uncompressed, and most of what it removes is redundancy deflate would have found
 * anyway, so a rate quoted only raw overstates what a user's transfer saves, by roughly a factor of
 * three here. Reporting both stops the flattering number being the only one on record.
 *
 * <p>Each row is minification <em>plus that one transform</em>, against the row above being
 * whitespace alone. They are not cumulative and they do not sum to the last row, because dropping a
 * zero's unit can leave a number for {@code compact-numbers} to shorten, so run together they
 * overlap.
 */
public final class MinifyRate {

    private static final SerializerOptions MINIFIED =
        SerializerOptions.builder().formatting(Formatting.MINIFIED).build();

    private MinifyRate() {
        // static-only
    }

    public static void main(String[] args) {
        Map<String, List<NodeTransform<?>>> steps = new LinkedHashMap<>();
        steps.put("minify (whitespace, comments)", List.of());
        steps.put("  + lowercase-names", List.of(Optimizations.lowercaseNames()));
        steps.put("  + shorten-colors", List.of(Optimizations.shortenColors()));
        steps.put("  + drop-zero-units", List.of(Optimizations.dropZeroUnits()));
        steps.put("  + compact-numbers", List.of(Optimizations.compactNumbers()));
        steps.put("  + drop-charset", List.of(Optimizations.dropCharset()));
        steps.put("minify -O (all of the above)", Optimizations.all());

        for (Corpus corpus : Corpus.values()) {
            if (!corpus.isAvailable()) {
                System.out.printf("%s: not fetched; see src/jmh/resources/corpus/README.md%n%n", corpus);
                continue;
            }

            report(corpus, steps);
        }
    }

    private static void report(Corpus corpus, Map<String, List<NodeTransform<?>>> steps) {
        byte[] source = corpus.bytes();
        int sourceZipped = gzip(source).length;
        Stylesheet ast = CssParser.parse(source).ast();

        System.out.printf("%s: %,d B, %,d B gzipped%n", corpus, source.length, sourceZipped);
        System.out.printf("  %-32s %10s %9s %10s %9s%n", "", "bytes", "of source", "gzipped", "of source");

        for (Map.Entry<String, List<NodeTransform<?>>> step : steps.entrySet()) {
            byte[] out = CssSerializer.serialize(Optimizer.optimize(ast, step.getValue()), MINIFIED)
                                      .getBytes(StandardCharsets.UTF_8);
            int zipped = gzip(out).length;
            System.out.printf("  %-32s %10d %8.1f%% %10d %8.1f%%%n",
                              step.getKey(),
                              out.length,
                              percent(out.length, source.length),
                              zipped,
                              percent(zipped, sourceZipped));
        }

        System.out.println();
    }

    private static double percent(int part, int whole) {
        return 100.0 * part / whole;
    }

    /**
     * Deflated at the default level, which is what a web server uses.
     *
     * <p>Not {@code -9}: the point is what a reader would actually be served, and the gap between
     * the two levels is under a percent on CSS.
     */
    private static byte[] gzip(byte[] data) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(data.length / 4);
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(data);
        }
        catch (IOException impossible) {
            // A ByteArrayOutputStream does not throw.
            throw new UncheckedIOException(impossible);
        }

        return bytes.toByteArray();
    }
}
