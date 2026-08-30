package dev.nullkitty.cassette.jmh;

import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.nullkitty.cassette.parser.CssParser;

/**
 * Where a parsed tree's bytes go: records, the lists holding them, and the strings inside.
 *
 * <p>The companion to the JMH suite, answering the question the gc profiler cannot.
 * {@code gc.alloc.rate.norm} says how much a parse allocates; this says how much of it the
 * caller is still holding afterwards, and which kinds of object it is held in. The two
 * disagree by a factor of three; a parse of Bootstrap allocates about 10 MB and retains
 * about 3.9 MB, and optimizations aimed at one do not automatically help the other.
 *
 * <p>Run it with {@code ./gradlew memoryCensus}.
 *
 * <p>Sizes are a model, not a measurement: 64-bit, compressed oops, 8-byte alignment. Good
 * enough to rank the costs against each other, which is all it is for. Anything decided on
 * these numbers should be confirmed with the benchmark that measures it directly.
 */
public final class MemoryCensus {

    /**
     * Object header, in bytes, under compressed oops.
     */
    private static final int HEADER = 12;

    /**
     * A {@code String}: header, coder, hash and the reference, aligned.
     */
    private static final int STRING_SHELL = 24;

    /**
     * An immutable list: the list object plus its backing array's header.
     */
    private static final int LIST_SHELL = 16;

    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();

    /**
     * By identity, not by value: two equal strings the tree holds separately cost twice.
     */
    private final IdentityHashMap<String, Boolean> stringInstances = new IdentityHashMap<>();

    private long                      stringReferences;
    private final Map<String, long[]> records = new HashMap<>();
    private long                      listBytes;
    private long                      listCount;

    public static void main(String[] args) {
        for (Corpus corpus : Corpus.values()) {
            if (!corpus.isAvailable()) {
                System.out.printf("%s: not fetched; see src/jmh/resources/corpus/README.md%n%n", corpus);
                continue;
            }

            byte[] source = corpus.bytes();

            MemoryCensus census = new MemoryCensus();
            census.walk(CssParser.parse(source).ast());
            census.report(corpus, source.length);
        }
    }

    private void walk(Object value) {
        switch (value) {
            case null -> {
                // A null component: an at-rule with no block, a selector-less pseudo-class.
            }

            case String text -> {
                this.stringReferences++;
                this.stringInstances.put(text, Boolean.TRUE);
            }

            case List<?> list -> {
                if (this.seen.put(list, Boolean.TRUE) == null) {
                    this.listCount++;
                    this.listBytes += LIST_SHELL + align(HEADER + 4L + 4L * list.size());
                    list.forEach(this::walk);
                }
            }

            case Record record -> {
                if (this.seen.put(record, Boolean.TRUE) != null) {
                    return;
                }

                RecordComponent[] components = record.getClass().getRecordComponents();
                long size = HEADER;
                for (RecordComponent component : components) {
                    size += widthOf(component.getType());
                }

                long[] tally = this.records.computeIfAbsent(record.getClass().getSimpleName(), ignored -> new long[2]);
                tally[0]++;
                tally[1] += align(size);

                for (RecordComponent component : components) {
                    walk(read(record, component));
                }
            }
            default -> {
                // Enums and boxed primitives: shared, or too small to matter.
            }
        }
    }

    private static Object read(Record record, RecordComponent component) {
        try {
            return component.getAccessor().invoke(record);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed reading " + component, e);
        }
    }

    private static long widthOf(Class<?> type) {
        if (type == double.class || type == long.class) {
            return 8;
        }

        if (type == boolean.class || type == byte.class) {
            return 1;
        }

        if (type == char.class || type == short.class) {
            return 2;
        }

        return 4; // int, and every reference under compressed oops
    }

    private void report(Corpus corpus, int sourceBytes) {
        Set<String> instances = this.stringInstances.keySet();
        Set<String> distinctTexts = new HashSet<>(instances);
        long stringBytes = 0;
        for (String text : instances) {
            stringBytes += stringSize(text);
        }

        long ifFullyShared = 0;
        for (String text : distinctTexts) {
            ifFullyShared += stringSize(text);
        }

        long recordBytes = 0;
        long recordCount = 0;
        for (long[] tally : this.records.values()) {
            recordCount += tally[0];
            recordBytes += tally[1];
        }

        long total = recordBytes + this.listBytes + stringBytes;

        System.out.printf("%s: %,d source bytes%n", corpus, sourceBytes);
        line("records", recordCount, recordBytes, total, sourceBytes);
        line("lists", this.listCount, this.listBytes, total, sourceBytes);
        line("strings", instances.size(), stringBytes, total, sourceBytes);

        System.out.printf("  %-9s %31s %,12d B  %.1fx source%n", "total", "", total, (double) total / sourceBytes);

        // If these three disagree, the intern table's length cap is why: everything longer
        // than it is materialized per occurrence, on purpose.
        System.out.printf("  %,d string references to %,d instances of %,d distinct texts"
                          + " (+%,d B over full sharing)%n",
                          this.stringReferences,
                          instances.size(),
                          distinctTexts.size(),
                          stringBytes - ifFullyShared);

        System.out.println("  largest record types:");
        this.records.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(6)
                    .forEach(entry -> System.out.printf("    %-20s %,9d  %,12d B%n",
                                                        entry.getKey(),
                                                        entry.getValue()[0],
                                                        entry.getValue()[1]));

        System.out.println();
    }

    private void line(String label, long count, long bytes, long total, int sourceBytes) {
        System.out.printf("  %-9s %,12d objects %,12d B  %5.1f%%  %.1fx source%n",
                          label,
                          count,
                          bytes,
                          100.0 * bytes / total,
                          (double) bytes / sourceBytes);
    }

    /**
     * Compact strings: a LATIN1 string of n characters occupies n bytes of payload.
     */
    private static long stringSize(String text) {
        return STRING_SHELL + align(HEADER + 4L + text.length());
    }

    private static long align(long size) {
        return (size + 7) / 8 * 8;
    }
}
