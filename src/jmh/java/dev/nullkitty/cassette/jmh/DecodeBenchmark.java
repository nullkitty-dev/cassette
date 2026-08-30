package dev.nullkitty.cassette.jmh;

import java.nio.charset.StandardCharsets;
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
 * Baseline: what the upfront decode alone costs, without any tokenizing.
 *
 * <p>Decoding the whole input into one {@code char[]} is a design commitment, so this is the
 * floor every parse benchmark is measured against. If tokenizing is not comfortably above this
 * line, the bottleneck is not the parser.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
public class DecodeBenchmark {

    @Param({ "SMALL", "MEDIUM", "LARGE" })
    public Corpus corpus;

    private byte[] source;

    @Setup
    public void loadCorpus() {
        this.source = this.corpus.bytes();
    }

    @Benchmark
    public void decodeUtf8(Blackhole blackhole) {
        blackhole.consume(new String(this.source, StandardCharsets.UTF_8));
    }
}
