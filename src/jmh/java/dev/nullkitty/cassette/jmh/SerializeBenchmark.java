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
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.serializer.CssSerializer;
import dev.nullkitty.cassette.serializer.Formatting;
import dev.nullkitty.cassette.serializer.IdentifierEncoding;
import dev.nullkitty.cassette.serializer.NestingMode;
import dev.nullkitty.cassette.serializer.Optimizations;
import dev.nullkitty.cassette.serializer.Optimizer;
import dev.nullkitty.cassette.serializer.SerializerOptions;

/**
 * The way back out, per output axis, from a tree parsed once in setup.
 *
 * <p>The comparisons worth making are between the benchmarks, not within one:
 *
 * <ul>
 *   <li>{@link #pretty} against {@link #minified}, what indentation and comments cost.</li>
 *   <li>{@link #minified} against {@link #flattened}, what rewriting every nested selector
 *       costs, since flattening rebuilds the rule tree before a byte is written.</li>
 *   <li>{@link #optimize} on its own, the fused transform pass, whose allocation should be
 *       one partial tree rebuild and not one per enabled transform.</li>
 * </ul>
 *
 * <p>Note that the corpus is nearly flat CSS: Bootstrap and Tailwind predate nesting, so
 * {@link #flattened} mostly measures the walk that finds nothing to do. That is the honest
 * common case, and {@code small-handwritten.css} is the entry that actually nests.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
public class SerializeBenchmark {

    private static final SerializerOptions PRETTY = SerializerOptions.DEFAULTS;

    private static final SerializerOptions MINIFIED = SerializerOptions.builder() //
                                                                       .formatting(Formatting.MINIFIED) //
                                                                       .build();

    private static final SerializerOptions FLATTENED = SerializerOptions.builder() //
                                                                        .nesting(NestingMode.FLATTEN) //
                                                                        .formatting(Formatting.MINIFIED) //
                                                                        .build();

    private static final SerializerOptions LEGACY = SerializerOptions.builder() //
                                                                     .legacyCompatible() //
                                                                     .formatting(Formatting.MINIFIED) //
                                                                     .build();

    @Param({ "SMALL", "MEDIUM", "LARGE" })
    public Corpus corpus;

    private Stylesheet ast;

    @Setup
    public void parseCorpus() {
        this.ast = CssParser.parse(this.corpus.bytes()).ast();
    }

    @Benchmark
    public void pretty(Blackhole blackhole) {
        blackhole.consume(CssSerializer.serialize(this.ast, PRETTY));
    }

    @Benchmark
    public void minified(Blackhole blackhole) {
        blackhole.consume(CssSerializer.serialize(this.ast, MINIFIED));
    }

    @Benchmark
    public void flattened(Blackhole blackhole) {
        blackhole.consume(CssSerializer.serialize(this.ast, FLATTENED));
    }

    /**
     * Flattened, duplicated instead of {@code :is()}-wrapped, and ASCII-escaped.
     */
    @Benchmark
    public void legacy(Blackhole blackhole) {
        blackhole.consume(CssSerializer.serialize(this.ast, LEGACY));
    }

    /**
     * The transform pass alone: no text written, just the rebuilt tree.
     */
    @Benchmark
    public void optimize(Blackhole blackhole) {
        blackhole.consume(Optimizer.optimize(this.ast, Optimizations.all()));
    }

    /**
     * ASCII-escaping every non-ASCII identifier, against {@link #minified}'s literal output.
     */
    @Benchmark
    public void asciiEscaped(Blackhole blackhole) {
        blackhole.consume(CssSerializer.serialize(this.ast,
                                                  SerializerOptions.builder().formatting(Formatting.MINIFIED)
                                                                   .identifierEncoding(IdentifierEncoding.ASCII)
                                                                   .build()));
    }
}
