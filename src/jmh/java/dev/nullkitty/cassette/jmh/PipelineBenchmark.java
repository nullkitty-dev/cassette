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
import dev.nullkitty.cassette.serializer.NestingMode;
import dev.nullkitty.cassette.serializer.Optimizations;
import dev.nullkitty.cassette.serializer.Optimizer;
import dev.nullkitty.cassette.serializer.SerializerOptions;

/**
 * What an embedding build tool actually runs: bytes in, minified CSS out.
 *
 * <p>Every other benchmark isolates a stage with its input prepared in setup, which is the
 * only way to attribute cost, but no caller ever runs a stage in isolation. This is the
 * figure to quote, and the one a regression anywhere will show up in.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
public class PipelineBenchmark {

    private static final SerializerOptions MINIFIED =
        SerializerOptions.builder().nesting(NestingMode.FLATTEN).formatting(Formatting.MINIFIED).build();

    @Param({ "SMALL", "MEDIUM", "LARGE" })
    public Corpus corpus;

    private byte[] bytes;

    @Setup
    public void loadCorpus() {
        this.bytes = this.corpus.bytes();
    }

    /**
     * Parse, flatten, optimize.
     */
    @Benchmark
    public void parseAndSerialize(Blackhole blackhole) {
        Stylesheet ast = CssParser.parse(this.bytes).ast();
        blackhole.consume(CssSerializer.serialize(ast, MINIFIED));
    }

    /**
     * The same, with every shipped optimization enabled.
     */
    @Benchmark
    public void parseOptimizeAndSerialize(Blackhole blackhole) {
        Stylesheet ast = CssParser.parse(this.bytes).ast();
        blackhole.consume(CssSerializer.serialize(Optimizer.optimize(ast, Optimizations.all()), MINIFIED));
    }
}
