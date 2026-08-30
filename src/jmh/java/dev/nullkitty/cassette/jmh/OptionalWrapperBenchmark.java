package dev.nullkitty.cassette.jmh;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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

import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.bundle.Importer;
import dev.nullkitty.cassette.bundle.Origin;
import dev.nullkitty.cassette.bundle.Source;
import dev.nullkitty.cassette.diagnostics.LineIndex;
import dev.nullkitty.cassette.diagnostics.SourceResolver;

/**
 * What the {@code Optional} on a returning boundary costs, isolated from everything it is
 * normally buried under.
 *
 * <p>The library wraps a return in {@code Optional} at exactly two boundaries a caller
 * implements or consumes, {@link Importer#resolve} and {@link SourceResolver#tryLocate}, and
 * returns a bare {@code null} everywhere internally. Whether that wrapper costs anything is a
 * question with a deadline: both types are exported, so it is cheaper to answer before 1.0
 * starts a compatibility policy than after.
 *
 * <p>Not a claim that either shape occurs at this density. A real {@code Importer.resolve} is
 * followed by a decode and a parse of what it returned, which is microseconds against these
 * nanoseconds. This is the ceiling: whatever the wrapper costs, it cannot cost more than it costs
 * here, and a ceiling low enough to ignore settles the question without a macro run. The existing
 * suite cannot settle it, because sixteen resolutions of {@code BundleBenchmark.resolveImports} put
 * 256 B against a 6.16 MB operation, three orders of magnitude under a noise floor of 2.2%.
 *
 * <p>Read {@code gc.alloc.rate.norm} first, not the time. Both consuming shapes are
 * scalar-replacement candidates, and if C2 takes them the paired arms allocate byte-for-byte the
 * same and there is nothing left to discuss. At {@link #CALLS} calls per op a surviving wrapper is
 * about 4 kB/op, which is unmistakable. Only if the bytes survive is the timing worth reading, and
 * then the bar is 2.2% reproduced across both forks.
 *
 * <h2>The two shapes, and why they are not one benchmark</h2>
 *
 * <p>They differ in what happens to the payload, which is the whole question, because escape
 * analysis is per allocation and a wrapper may be scalarized while its referent is not.
 *
 * <table border="1">
 * <caption>what each pair isolates</caption>
 * <tr><th>pair</th><th>models</th><th>the payload</th></tr>
 * <tr><td>{@link #resolveViaOptional} / {@link #resolveViaNullable}</td>
 *     <td>{@code BundleOptions.resolve} into {@code Bundler.Assembly.resolve}</td>
 *     <td>already exists; only the wrapper is allocated per call</td></tr>
 * <tr><td>{@link #locateViaOptional} / {@link #locateViaNullable}</td>
 *     <td>{@code MapPass}, per mapping</td>
 *     <td>allocated per call <em>and</em> escapes into a capturing lambda</td></tr>
 * </table>
 *
 * <p>Within each pair the arms differ by exactly the wrapper, so the delta needs no arithmetic to
 * attribute. Across the pairs they differ by what the payload does, so the second pair is the one
 * that says whether a wrapper around an escaping object escapes with it.
 *
 * <p>The second pair is why this class exists at all. The {@code Optional} plus {@code Location}
 * pair was recorded as the largest single term in building a map, 46% on Bootstrap and 43% on
 * Tailwind, and that figure was constructed as sixteen bytes for the wrapper plus thirty-two for
 * the record. That is arithmetic over a measured total rather than two measured terms. The
 * {@code Location} half certainly survives, since {@code MapPass} captured it in a
 * {@code computeIfAbsent} lambda, which defeats scalar replacement outright. The wrapper half is
 * what was in doubt, and if it is free the term is 32 B per mapping rather than 48 and the
 * percentage is nearer 30.
 *
 * <p>Both call sites are monomorphic, which is the honest case. One {@code Bundler} run resolves
 * every import through one importer, and one map is built against one resolver.
 *
 * <p>This class takes no corpus, so it needs neither gitignored entry, and {@code -Pjmh.corpus}
 * must not be passed with it. That property sets a benchmark parameter named {@code corpus}, which
 * no benchmark here declares.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
public class OptionalWrapperBenchmark {

    /**
     * Calls per op. High enough that JMH's own per-op overhead is not the thing being measured,
     * low enough to stay a microbenchmark, and enough that a surviving wrapper is kilobytes.
     */
    private static final int CALLS = 256;

    /**
     * Distinct sources the locating pair indexes by, which is the shape of a real bundle: a
     * handful of files against hundreds of thousands of mappings.
     */
    private static final int SOURCES = 4;

    /**
     * Characters per source. Only has to be long enough to hold every span.
     */
    private static final int TEXT_LENGTH = 4096;

    /**
     * Characters a located span covers. Arbitrary, and small enough that no span built here can
     * straddle the end of its source.
     */
    private static final int SPAN_LENGTH = 8;

    /**
     * A nullable-returning importer, the shape under evaluation. It lives here rather than in
     * {@code main} so that pricing the alternative costs no public API.
     */
    @FunctionalInterface
    interface NullableImporter {

        Source resolve(String specifier, Origin from);
    }

    /**
     * The locating boundary as {@code SourceIndex} overrides it, answered from a range check it
     * already computes.
     *
     * <p>Declared here rather than used through {@link SourceResolver} because a lambda can only
     * implement that interface's abstract {@code locate}, which would measure the default
     * {@code tryLocate}'s catch of an {@code IndexOutOfBoundsException} — a path
     * {@code MapPass} never takes, since the resolver it is handed overrides it.
     */
    @FunctionalInterface
    interface OptionalLocator {

        Optional<SourceResolver.Location> tryLocate(SourceSpan span);
    }

    /**
     * The same, returning the location or {@code null}.
     */
    @FunctionalInterface
    interface NullableLocator {

        SourceResolver.Location tryLocate(SourceSpan span);
    }

    /**
     * What fraction of calls resolve.
     *
     * <p>{@code 100} is the allocating path throughout and is what {@code MapPass} really sees,
     * an unresolvable span being a bundler wrapper and rare. {@code 0} is all declines, where
     * {@code Optional.empty()} is a singleton and no location is built, so the paired arms should be
     * indistinguishable. That arm is the negative control: a gap in it means this benchmark is
     * measuring something other than what it claims, and the other two params are evidence of
     * nothing.
     */
    @Param({ "100", "50", "0" })
    public int hitPercent;

    private String[] specifiers;

    private Origin origin;

    private Importer optionalImporter;

    private NullableImporter nullableImporter;

    private SourceSpan[] spans;

    private OptionalLocator optionalLocator;

    private NullableLocator nullableLocator;

    /**
     * Pre-populated, so {@code computeIfAbsent} always hits and no {@link LineIndex} is built
     * inside a measured loop.
     *
     * <p>The faithful shape rather than a convenience. {@code MapPass} builds one index per source
     * and consults it once per mapping, so on any real sheet the construction is amortized to
     * nothing. What stays per call is the capturing lambda handed to {@code computeIfAbsent}, which
     * is kept because it is part of what the paired arms hold identical.
     */
    private Map<String, LineIndex> lineIndexes;

    @Setup
    public void setUp() {
        Map<String, Source> byId = new HashMap<>();
        this.specifiers = new String[CALLS];

        for (int at = 0; at < CALLS; at++) {
            String id = "chunk-" + at;
            this.specifiers[at] = id;
            if (hits(at)) {
                byId.put(id, new Source(id, "a{color:red}".getBytes(StandardCharsets.UTF_8)));
            }
        }

        this.origin = new Origin("entry", 0);
        this.optionalImporter = (specifier, from) -> Optional.ofNullable(byId.get(specifier));
        this.nullableImporter = (specifier, from) -> byId.get(specifier);

        String[] ids = new String[SOURCES];
        CharSequence[] texts = new CharSequence[SOURCES];
        this.lineIndexes = new HashMap<>();

        for (int source = 0; source < SOURCES; source++) {
            ids[source] = "source-" + source;
            texts[source] = text(source);
            this.lineIndexes.put(ids[source], new LineIndex(texts[source]));
        }

        this.spans = new SourceSpan[CALLS];

        for (int at = 0; at < CALLS; at++) {
            int source = at % SOURCES;

            // A miss is a span past the last source, which is how a bundler's wrapper misses:
            // no segment holds the whole of it.
            this.spans[at] =
                hits(at) ? new SourceSpan(source * TEXT_LENGTH + (at * 7) % (TEXT_LENGTH - SPAN_LENGTH), SPAN_LENGTH)
                         : new SourceSpan(SOURCES * TEXT_LENGTH + at, SPAN_LENGTH);
        }

        this.optionalLocator = span -> {
            int source = span.start() / TEXT_LENGTH;
            return source >= SOURCES ? Optional.empty()
                                     : Optional.of(new SourceResolver.Location(ids[source],
                                                                               texts[source],
                                                                               span.start() - source * TEXT_LENGTH,
                                                                               span.length()));
        };

        this.nullableLocator = span -> {
            int source = span.start() / TEXT_LENGTH;
            return source >= SOURCES ? null
                                     : new SourceResolver.Location(ids[source],
                                                                   texts[source],
                                                                   span.start() - source * TEXT_LENGTH,
                                                                   span.length());
        };
    }

    /**
     * Today's importer shape, including the {@code requireNonNull} that
     * {@code BundleOptions.resolve} pays, so the comparison is against what runs rather than a
     * tidied version of it.
     */
    @Benchmark
    public void resolveViaOptional(Blackhole blackhole) {
        for (String specifier : this.specifiers) {
            Optional<Source> imported =
                Objects.requireNonNull(this.optionalImporter.resolve(specifier, this.origin),
                                       "an importer returned null; return Optional.empty() to decline");
            if (imported.isEmpty()) {
                blackhole.consume(specifier);
                continue;
            }

            blackhole.consume(imported.get());
        }
    }

    /**
     * The proposed importer shape: a bare reference and a null test.
     *
     * <p>No {@code requireNonNull}, and that is not an omission. Under a nullable contract
     * {@code null} legitimately means declined, so the check does not move — it disappears, and
     * a before-and-after that kept it would be measuring neither shape.
     */
    @Benchmark
    public void resolveViaNullable(Blackhole blackhole) {
        for (String specifier : this.specifiers) {
            Source imported = this.nullableImporter.resolve(specifier, this.origin);
            if (imported == null) {
                blackhole.consume(specifier);
                continue;
            }

            blackhole.consume(imported);
        }
    }

    /**
     * {@code MapPass}'s per-mapping locate, as written today.
     *
     * <p>Call, {@code isEmpty}, {@code get}, then capture the unwrapped location in the lambda
     * {@code computeIfAbsent} takes, which is what makes the payload escape.
     */
    @Benchmark
    public void locateViaOptional(Blackhole blackhole) {
        for (SourceSpan span : this.spans) {
            Optional<SourceResolver.Location> located = this.optionalLocator.tryLocate(span);
            if (located.isEmpty()) {
                continue;
            }

            SourceResolver.Location at = located.get();
            LineIndex lines = this.lineIndexes.computeIfAbsent(at.sourceId(), id -> new LineIndex(at.sourceText()));

            blackhole.consume(lines.lineOf(at.offset()));
            blackhole.consume(lines.columnOf(at.offset()));
        }
    }

    /**
     * The same, with the wrapper removed and nothing else changed.
     */
    @Benchmark
    public void locateViaNullable(Blackhole blackhole) {
        for (SourceSpan span : this.spans) {
            SourceResolver.Location at = this.nullableLocator.tryLocate(span);
            if (at == null) {
                continue;
            }

            LineIndex lines = this.lineIndexes.computeIfAbsent(at.sourceId(), id -> new LineIndex(at.sourceText()));

            blackhole.consume(lines.lineOf(at.offset()));
            blackhole.consume(lines.columnOf(at.offset()));
        }
    }

    /**
     * The same again, with the capture removed as well.
     *
     * <p>{@code computeIfAbsent} takes a lambda capturing the location, so a lambda object is
     * allocated at the call site whether or not the map already holds the index, and a real map
     * build does hold it, since there are a handful of sources against hundreds of thousands of
     * mappings. A {@code get} answers the hit without capturing anything, which is the same
     * semantics for a single-threaded walk.
     *
     * <p>This arm minus {@link #locateViaNullable} is therefore the capture, and this arm alone is
     * the {@code Location} record. With {@link #locateViaOptional} on top of both, every term in the
     * per-mapping figure is measured rather than inferred.
     */
    @Benchmark
    public void locateWithoutCapture(Blackhole blackhole) {
        for (SourceSpan span : this.spans) {
            SourceResolver.Location at = this.nullableLocator.tryLocate(span);
            if (at == null) {
                continue;
            }

            LineIndex lines = this.lineIndexes.get(at.sourceId());
            if (lines == null) {
                lines = new LineIndex(at.sourceText());
                this.lineIndexes.put(at.sourceId(), lines);
            }

            blackhole.consume(lines.lineOf(at.offset()));
            blackhole.consume(lines.columnOf(at.offset()));
        }
    }

    /**
     * Whether the call at {@code index} resolves, so that both pairs decline in the same places.
     */
    private boolean hits(int index) {
        return index * 100 < CALLS * this.hitPercent;
    }

    /**
     * Text for one source: CSS-shaped and newline-bearing, so a {@link LineIndex} over it has
     * something to index.
     */
    private static CharSequence text(int source) {
        StringBuilder text = new StringBuilder(TEXT_LENGTH);
        for (int rule = 0; text.length() < TEXT_LENGTH; rule++) {
            text.append(".s").append(source).append("-r").append(rule).append(" { color: red }\n");
        }

        return text.substring(0, TEXT_LENGTH);
    }
}
