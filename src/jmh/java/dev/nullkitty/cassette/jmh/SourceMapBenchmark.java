package dev.nullkitty.cassette.jmh;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.diagnostics.SourceResolver;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.parser.DecodedSource;
import dev.nullkitty.cassette.serializer.CssSerializer;
import dev.nullkitty.cassette.serializer.Formatting;
import dev.nullkitty.cassette.serializer.SerializeResult;
import dev.nullkitty.cassette.serializer.SerializerOptions;
import dev.nullkitty.cassette.sourcemap.SourceMap;

/**
 * What a source map costs, which is a development cost and not a production one.
 *
 * <p>Production is the maps-off path, and the CSS is byte-identical either way, so nothing here
 * gates a release. It exists because the on-cost was estimated and never measured, and an estimate
 * that stays unmeasured is the shape of the mistake the token buffer's sizing already made once.
 *
 * <p>The comparisons are between the benchmarks, and each isolates one term. A map's cost splits in
 * two, because {@code sourcesContent} costs nothing until {@code toJson}, so measuring the whole
 * thing at once would blur the halves together:
 *
 * <ul>
 *   <li>{@link #minified} against {@link #withMap}, what <em>building</em> a map costs. This is
 *       the mapping arrays, the resolving pass and the VLQ, and it is the part a caller pays even
 *       if the map is thrown away.</li>
 *   <li>{@link #withMap} against {@link #withMapToJson}, what <em>rendering</em> it costs, which
 *       is where the source text is finally copied.</li>
 *   <li>{@link #withMapToJson} against {@link #withMapToJsonWithoutContent}, the share that is
 *       {@code sourcesContent} alone, and therefore what {@code --no-source-map-content} removes.
 *       This is the case that holds the claim that it is the dominant term.</li>
 * </ul>
 *
 * <p>{@link #minified} duplicates {@code SerializeBenchmark.minified}, and has to. The delta is
 * only worth anything if both sides ran in the same fork off the same tree, and a figure lifted from
 * another class in another fork is a cross-session comparison. Allocation figures here have drifted
 * 19.5% between runs on code neither side can reach, so only a difference taken inside one run
 * means anything.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
public class SourceMapBenchmark {

    private static final SerializerOptions MINIFIED =
        SerializerOptions.builder().formatting(Formatting.MINIFIED).build();

    @Param({ "SMALL", "MEDIUM", "LARGE" })
    public Corpus corpus;

    private Stylesheet ast;

    private SourceResolver sources;

    /**
     * Decoded once and kept, which is what a maps-on caller has to do anyway.
     *
     * <p>A resolver needs the text a span indexes into, so this is the one benchmark whose setup
     * cannot throw the decoded buffer away, {@code decodeSource} is the entry point that exists
     * for exactly this, and using it here keeps the setup honest about what the feature requires
     * of a caller rather than measuring a shortcut no real caller has.
     */
    @Setup
    public void parseCorpus() {
        DecodedSource decoded = CssParser.decodeSource(this.corpus.bytes(), null, 0, Diagnostic.DISCARD);
        this.ast = CssParser.parse(decoded).ast();
        this.sources = SourceResolver.of(this.corpus.name(), decoded.text());
    }

    /**
     * The control: the same options, no map.
     */
    @Benchmark
    public void minified(Blackhole blackhole) {
        blackhole.consume(CssSerializer.serialize(this.ast, MINIFIED));
    }

    /**
     * Building a map and not rendering it.
     */
    @Benchmark
    public void withMap(Blackhole blackhole) {
        blackhole.consume(CssSerializer.serializeWithMap(this.ast, MINIFIED, this.sources));
    }

    /**
     * Building it and rendering it, which is what a caller writing a sidecar pays.
     */
    @Benchmark
    public void withMapToJson(Blackhole blackhole) {
        SerializeResult result = CssSerializer.serializeWithMap(this.ast, MINIFIED, this.sources);
        blackhole.consume(result.css());
        blackhole.consume(result.sourceMap().toJson());
    }

    /**
     * The same, with {@code sourcesContent} dropped.
     *
     * <p>Dropping it is one record and no serializer option, because the content is a reference
     * to text that already exists until {@code toJson} copies it, so this measures the copy and
     * nothing else.
     */
    @Benchmark
    public void withMapToJsonWithoutContent(Blackhole blackhole) {
        SerializeResult result = CssSerializer.serializeWithMap(this.ast, MINIFIED, this.sources);
        SourceMap map = result.sourceMap();
        blackhole.consume(result.css());
        blackhole.consume(new SourceMap(null, null, map.sources(), null, map.mappings()).toJson());
    }
}
