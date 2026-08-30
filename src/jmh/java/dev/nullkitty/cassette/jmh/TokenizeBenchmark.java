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

import dev.nullkitty.cassette.lexer.SourceText;
import dev.nullkitty.cassette.lexer.TokenType;
import dev.nullkitty.cassette.lexer.Tokenizer;

/**
 * Tokenizer throughput and, more importantly, allocation rate.
 *
 * <p>{@link #scan} is the number to watch: the cursor allocates nothing per token, so its
 * {@code gc.alloc.rate.norm} should stay flat regardless of how many tokens the corpus
 * holds. A jump there means something started materializing per-token state.
 *
 * <p>{@link #decodeAndScan} adds the upfront decode, and so is the honest end-to-end figure;
 * compare it against {@link DecodeBenchmark} to see what tokenizing costs over the floor.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
public class TokenizeBenchmark {

    @Param({ "SMALL", "MEDIUM", "LARGE" })
    public Corpus corpus;

    private byte[] bytes;

    private SourceText source;

    @Setup
    public void loadCorpus() {
        this.bytes = this.corpus.bytes();
        this.source = SourceText.decode(this.bytes);
    }

    @Benchmark
    public void scan(Blackhole blackhole) {
        Tokenizer tokenizer = new Tokenizer(this.source);
        int tokens = 0;
        while (tokenizer.next() != TokenType.EOF) {
            tokens++;
        }

        blackhole.consume(tokens);
    }

    @Benchmark
    public void decodeAndScan(Blackhole blackhole) {
        Tokenizer tokenizer = new Tokenizer(SourceText.decode(this.bytes));
        int tokens = 0;
        while (tokenizer.next() != TokenType.EOF) {
            tokens++;
        }

        blackhole.consume(tokens);
    }

    /**
     * The same scan, but materializing every token's value: what a naive
     * record-per-token tokenizer would cost on top of {@link #scan}.
     */
    @Benchmark
    public void scanAndMaterializeValues(Blackhole blackhole) {
        Tokenizer tokenizer = new Tokenizer(this.source);
        while (tokenizer.next() != TokenType.EOF) {
            blackhole.consume(tokenizer.value());
        }
    }
}
