package dev.nullkitty.cassette.jmh;

import dev.nullkitty.cassette.lexer.SourceText;
import dev.nullkitty.cassette.lexer.TokenBuffer;
import dev.nullkitty.cassette.parser.CssParser;
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

/**
 * The headline number: bytes in, tree out.
 *
 * <p>The three benchmarks here are a ladder, and the gaps between them are the point.
 * {@link #tokenize} builds the token buffer without materializing a single value;
 * {@link #parse} adds the AST on top of exactly that work. Their difference is what the
 * tree costs, records, lists, and the strings the buffer had been careful not to create.
 *
 * <p>Watch {@code gc.alloc.rate.norm} more than the time. It is the metric this project
 * tracks, and the one that catches a regression a stopwatch cannot: an optimization pass
 * that rebuilds a subtree it did not need to, or a stray {@code String} in a hot loop.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
public class ParseBenchmark {

    @Param({ "SMALL", "MEDIUM", "LARGE" })
    public Corpus corpus;

    private byte[] bytes;

    private SourceText source;

    private String text;

    @Setup
    public void loadCorpus() {
        this.bytes = this.corpus.bytes();
        this.source = SourceText.decode(this.bytes);
        this.text = this.source.toString();
    }

    /**
     * Structure-of-arrays token buffer, from an already-decoded source.
     */
    @Benchmark
    public void tokenize(Blackhole blackhole) {
        blackhole.consume(TokenBuffer.tokenize(this.source));
    }

    /**
     * The same buffer plus the tree over it, entered through the already-decoded-text
     * overload, so this is {@link #tokenize} plus the AST, and one preprocessing copy.
     */
    @Benchmark
    public void parseText(Blackhole blackhole) {
        blackhole.consume(CssParser.parse(this.text));
    }

    /**
     * What a caller actually calls: charset detection, decode, tokenize, parse.
     */
    @Benchmark
    public void parse(Blackhole blackhole) {
        blackhole.consume(CssParser.parse(this.bytes));
    }
}
