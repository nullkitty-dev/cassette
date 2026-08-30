package dev.nullkitty.cassette.jmh;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.bundle.BundleOptions;
import dev.nullkitty.cassette.bundle.Bundler;
import dev.nullkitty.cassette.bundle.Importer;
import dev.nullkitty.cassette.bundle.Source;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.lexer.SourceText;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.parser.DecodedSource;

/**
 * Whether a bundle costs more than the files it is made of.
 *
 * <p>The claim under test is the one that justifies the whole coordinate-space design: bundling
 * allocates the sum of its sources' parses and nothing more. Each source is decoded and parsed
 * directly at the offset it occupies in the bundle, so no tree is ever rebased and no intermediate
 * tree exists. Threading a base offset through {@code SourceText}, {@code TokenBuffer} and the
 * public entry points is a real cost in complexity, paid to avoid a rewrite pass, and this is the
 * benchmark that says whether the thing bought was actually bought.
 *
 * <p>{@link #parseEach} is therefore the control rather than a curiosity. A rebase or an
 * intermediate tree would show up as roughly a second tree's worth of {@code gc.alloc.rate.norm},
 * which on MEDIUM is megabytes, far above any noise this benchmark carries. Watch the allocation,
 * not the time.
 *
 * <p>The claim holds, but only once the gap is attributed, and the first version of this benchmark
 * could not do that. Bundling first measured about 12% over the control: no second tree, but not
 * nothing either, and none of it the bundler's. Three rungs could show the gap and not whose it
 * was, which is an invitation to blame the wrong thing.
 *
 * <p>It divided out to three bytes per source character, which is arithmetic rather than
 * measurement. {@code CssParser.decode} returned a {@code String}, one byte per character while it
 * stays Latin-1, and {@code CssParser.parse(CharSequence, int)} copied that into a fresh
 * {@code char[]}, two more. The {@code String} is retained output rather than waste, since the
 * segment table holds it for the life of the bundle and a compact string retains half what the
 * buffer underneath would. The {@code char[]} was waste, because decode had already built one and
 * threw it away. {@code CssParser.decodeSource} closed it, and the gap between
 * {@link #decodeToTextThenParseEach} and {@link #decodeToSourceThenParseEach} is what that was
 * worth.
 *
 * <p>Where that leaves the ladder: bundling is about 4–5% over the control, of which 0.6% is the
 * bundler's own structures, meaning the segment table, the combined child list and the walk for
 * {@code @import} and {@code @charset}. The rest is that one retained byte per character. On LARGE
 * the middle rung sits 1.0001 bytes per source character above the control, which is the whole of
 * it and nothing else.
 *
 * <h2>The graph is the corpus cut up, not the corpus repeated</h2>
 *
 * <p>A synthetic graph of N copies of Bootstrap measures N parses of Bootstrap. Splitting one
 * corpus entry into N sources instead keeps the total text at exactly the corpus size, which
 * makes these numbers directly comparable with {@code ParseBenchmark.parse} on the same entry,
 * and it is the more faithful shape besides, since a real project's bundle is one stylesheet's
 * worth of CSS spread over many partials.
 *
 * <p>The cuts are found by the parser, not by a scanner written here. A brace counter in a
 * benchmark would have to get strings, comments and escapes right to avoid splitting mid-rule,
 * which is the tokenizer's job and not one worth doing twice. The entry is parsed once in setup and
 * cut at the span ends of its top-level children instead, so every chunk is a whole number of
 * top-level nodes and therefore a valid stylesheet on its own.
 *
 * <p>{@link #SOURCES} is a constant rather than a {@code @Param}, because a second axis triples a
 * suite that already runs for hours and the interesting per-source overhead is visible at one
 * value. {@link #cut} yields fewer sources than asked for when the top level will not divide, so
 * what it actually produces is 9 sources of 200–1200 B for SMALL, 16 of about 17.5 kB for MEDIUM
 * and 7 of 228–712 kB for LARGE. SMALL is the harshest per-source case the corpus can make and the
 * one where an overhead per source would show first.
 *
 * <h2>Reading the five numbers</h2>
 *
 * <p>A ladder, like {@code ParseBenchmark}'s, and the gaps between the rungs are the point. It is
 * not in ascending order: the second rung is the pairing the third replaced, and it sits above both
 * of them.
 *
 * <table border="1">
 * <caption>what each benchmark does, and what its gap to the control means</caption>
 * <tr><th>benchmark</th><th>what it runs</th><th>the gap it isolates</th></tr>
 * <tr><td>{@link #parseEach}</td><td>{@code parse(byte[])} per chunk</td>
 *     <td>nothing; this is the control</td></tr>
 * <tr><td>{@link #decodeToTextThenParseEach}</td><td>decode to a string, parse the string</td>
 *     <td>what the string pairing costs: 3 B per source character</td></tr>
 * <tr><td>{@link #decodeToSourceThenParseEach}</td><td>decode to a source, parse the source</td>
 *     <td>the retained text alone: 1 B per source character</td></tr>
 * <tr><td>{@link #concatenate}</td><td>{@code Bundler.bundle} over those chunks</td>
 *     <td>the bundler's own overhead, against the rung above</td></tr>
 * <tr><td>{@link #resolveImports}</td><td>an entry sheet importing them all</td>
 *     <td>the entry sheet, and resolution</td></tr>
 * </table>
 *
 * <p>Only the fourth gap tests the design claim. The others are there so it can be read alone.
 *
 * <p>{@link #resolveImports} parses one sheet more than every rung above it, a few hundred
 * bytes holding nothing but import rules, and the one asymmetry here that is expected rather
 * than suspicious. Its imports are bare specifiers, so the contents are spliced in with no
 * wrapper; a prelude implying {@code @media} or {@code @layer} allocates one group rule per
 * import, which is a per-import constant and not the thing being measured.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
public class BundleBenchmark {

    /**
     * How many sources the corpus entry is cut into.
     */
    private static final int SOURCES = 16;

    @Param({ "SMALL", "MEDIUM", "LARGE" })
    public Corpus corpus;

    private List<Source> chunks;

    private Source entry;

    private BundleOptions resolving;

    @Setup
    public void splitCorpus() {
        String text = SourceText.decode(this.corpus.bytes()).toString();
        this.chunks = cut(text, SOURCES);

        StringBuilder imports = new StringBuilder();
        Map<String, Source> byId = new HashMap<>();

        for (Source chunk : this.chunks) {
            imports.append("@import \"").append(chunk.id()).append("\";\n");
            byId.put(chunk.id(), chunk);
        }

        this.entry = new Source("entry", imports.toString().getBytes(StandardCharsets.UTF_8));

        Importer importer = (specifier, from) -> Optional.ofNullable(byId.get(specifier));
        this.resolving = BundleOptions.builder().importer(importer).build();
    }

    /**
     * The control: every chunk parsed on its own, which is the sum the claim is about.
     */
    @Benchmark
    public void parseEach(Blackhole blackhole) {
        for (Source chunk : this.chunks) {
            blackhole.consume(CssParser.parse(chunk.content()));
        }
    }

    /**
     * The two-step pairing by way of a {@code String}, which is what {@code Bundler} used to do.
     *
     * <p>Detect the encoding, decode to a {@code String}, parse from that string at a base, and
     * no bundler. Kept after the bundler stopped doing it, because the gap to
     * {@link #decodeToSourceThenParseEach} is the whole justification for
     * {@code CssParser.decodeSource} existing and is otherwise a claim in a document.
     */
    @Benchmark
    public void decodeToTextThenParseEach(Blackhole blackhole) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        int base = 0;

        for (Source chunk : this.chunks) {
            byte[] content = chunk.content();
            blackhole.consume(CssParser.detectEncoding(content, null));
            String text = CssParser.decode(content, null, base, diagnostics::add);
            blackhole.consume(CssParser.parse(text, base));
            base += text.length();
        }

        blackhole.consume(diagnostics);
    }

    /**
     * The pairing that keeps the decoded buffer, which is what {@code Bundler} does now.
     *
     * <p>Exactly {@code Bundler}'s per-source work minus the bundler, so the gap from here up to
     * {@link #concatenate} is the bundler's own structures and nothing else. That is the rung
     * the design claim is read off, and it exists because the first version of this benchmark
     * could not say whose the gap was, which invited the next reader to blame the wrong thing.
     *
     * <p>{@code text()} is called because the segment table retains it, so this pays the one
     * byte per character that survives and none of the two that did not.
     */
    @Benchmark
    public void decodeToSourceThenParseEach(Blackhole blackhole) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        int base = 0;
        for (Source chunk : this.chunks) {
            DecodedSource decoded = CssParser.decodeSource(chunk.content(), null, base, diagnostics::add);
            blackhole.consume(decoded.encoding());
            blackhole.consume(CssParser.parse(decoded));
            blackhole.consume(decoded.text());
            base += decoded.length();
        }

        blackhole.consume(diagnostics);
    }

    /**
     * Concatenation with no importer, over exactly the sources {@link #parseEach} parses.
     */
    @Benchmark
    public void concatenate(Blackhole blackhole) {
        blackhole.consume(Bundler.bundle(this.chunks));
    }

    /**
     * One entry sheet importing all of them, resolved from memory.
     */
    @Benchmark
    public void resolveImports(Blackhole blackhole) {
        blackhole.consume(Bundler.bundle(this.entry, this.resolving));
    }

    /**
     * Cuts decoded text into at most {@code parts} sources at top-level node boundaries.
     *
     * <p>Boundaries are span ends, so the text between two nodes, the whitespace and anything
     * the parser dropped, stays attached to the chunk before it and nothing is lost. Fewer
     * chunks than asked for when the stylesheet has fewer top-level nodes than that, which keeps
     * every chunk holding at least one.
     *
     * <p>Balanced by bytes and not by node count, which matters more than it sounds. Tailwind's 6618
     * top-level nodes are thousands of small ones followed by five {@code @media} blocks of roughly
     * 613 kB each, so cutting every {@code n}th node gives fifteen scraps and one 3.1 MB source, a
     * graph with the right number of files and none of the shape. Nodes are accumulated until a
     * chunk reaches its share and then cut, which produces fewer sources than asked for when the top
     * level is too coarse to divide further. LARGE settles at seven, and that is the corpus
     * reporting a fact about itself rather than a knob to tune. What the benchmark needs is that the
     * control parses exactly the sources the bundler is handed, and it does either way.
     */
    private static List<Source> cut(String text, int parts) {
        List<Node> children = CssParser.parse(text).ast().children();
        if (children.isEmpty()) {
            return List.of(new Source("chunk-0", text.getBytes(StandardCharsets.UTF_8)));
        }

        int share = Math.max(1, text.length() / parts);
        List<Source> sources = new ArrayList<>(parts);
        int start = 0;

        for (int i = 0; i < children.size(); i++) {
            boolean last = i == children.size() - 1;
            int end = children.get(i).span().end();
            if (last || end - start >= share) {
                sources.add(new Source("chunk-" + sources.size(),
                                       text.substring(start, last ? text.length() : end)
                                           .getBytes(StandardCharsets.UTF_8)));
                start = end;
            }
        }

        return sources;
    }
}
