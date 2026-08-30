package dev.nullkitty.cassette.serializer;

import java.util.Arrays;

import dev.nullkitty.cassette.ast.SourceSpan;

/**
 * Where in the output each mapped construct was written, and where it came from.
 *
 * <p>Two parallel primitive arrays, twelve bytes per mapping and no per-mapping object. The same
 * structure-of-arrays discipline {@code TokenBuffer} uses one level down, and for the same reason:
 * this is the one new allocation a source map costs per serialization, and allocation is a tracked
 * metric.
 *
 * <p>An output <em>offset</em> is recorded rather than a line and a column, because
 * {@link CssWriter} truncates its buffer in six places and a mapping list truncated to the same
 * offset is exactly correct, where a committed {@code (line, column)} pair would need separate
 * bookkeeping to un-commit. Offsets become lines and columns in one pass after the last character
 * is written.
 *
 * @see <a href="https://tc39.es/ecma426/#sec-mappings">Source Map (ECMA-426) §9.2 Mappings structure</a>
 */
final class Mappings {

    /**
     * How many mappings a source character is expected to produce.
     *
     * <p>Counted over the three corpus entries and a nested authored sample: 0.027–0.031 for
     * compiled, flat CSS, and 0.042 for authored CSS with nesting, which is the shape a build
     * generating a map hands in. Nesting raises the count and lowers the character total at the
     * same time, because a nested rule's prelude is {@code &:hover} where the flat rule it
     * compiles to repeats the whole ancestor chain, so the corpus alone understates this by a
     * third.
     *
     * <p>Above every measurement but one, since overshooting discards an array and undershooting
     * copies everything recorded so far. The exception is input already minified once and then
     * processed as nesting, which no pipeline does and which costs one doubling if it happens.
     */
    private static final double PER_SOURCE_CHARACTER = 0.05;

    /**
     * The floor, for the trees the estimate cannot see: a hand-built one carries
     * {@link SourceSpan#NONE} and reports no length at all.
     */
    private static final int MINIMUM_CAPACITY = 64;

    private int[]  outputOffsets;
    private long[] packedSpans;
    private int    size;

    /**
     * How many mappings the arrays are sized for, before anything is written.
     *
     * <p>Package-private so the sizing test can assert a real stylesheet never exceeds it.
     * Growth copies everything recorded so far, which {@link #PER_SOURCE_CHARACTER} is sized to
     * avoid, on the same reasoning and behind the same guard as
     * {@code TokenBuffer.estimateCapacity}.
     *
     * @param sourceCharacters the stylesheet's own span length
     * @return the capacity both arrays are given
     */
    static int estimateCapacity(int sourceCharacters) {
        return Math.max(MINIMUM_CAPACITY, (int) (sourceCharacters * PER_SOURCE_CHARACTER));
    }

    Mappings(int sourceCharacters) {
        int capacity = estimateCapacity(sourceCharacters);

        this.outputOffsets = new int[capacity];
        this.packedSpans = new long[capacity];
    }

    /**
     * Records that the construct spanning {@code packedSpan} starts at {@code outputOffset}.
     *
     * <p>A span of zero length is dropped here rather than at encoding time. {@code NONE_PACKED}
     * is {@code 0L} and so is {@code pack(0, 0)}, which makes a synthesized node bit-identical to
     * a real zero-width node at the start of the first source, so the length is the only test
     * available, and a construct covering no characters has nothing to point at anyway.
     *
     * <p>Offsets arrive in non-decreasing order, because the writer only ever appends. That is
     * what {@link #truncateFrom} relies on.
     */
    void add(int outputOffset, //
             long packedSpan) {
        if (SourceSpan.lengthOf(packedSpan) == 0) {
            return;
        }

        if (this.size == this.outputOffsets.length) {
            int grown = this.outputOffsets.length * 2;
            this.outputOffsets = Arrays.copyOf(this.outputOffsets, grown);
            this.packedSpans = Arrays.copyOf(this.packedSpans, grown);
        }

        this.outputOffsets[this.size] = outputOffset;
        this.packedSpans[this.size] = packedSpan;

        this.size++;
    }

    /**
     * Drops every mapping at or after {@code outputOffset}, which is what the writer taking
     * output back has to mean for the mappings recorded inside it.
     *
     * <p>At the offset and not merely after it: text truncated to a mark is replaced by
     * whatever is written next, so a mapping sitting exactly on the mark would come to describe
     * something else.
     */
    void truncateFrom(int outputOffset) {
        while (this.size > 0 && this.outputOffsets[this.size - 1] >= outputOffset) {
            this.size--;
        }
    }

    int size() {
        return this.size;
    }

    /**
     * Current array length, for the sizing test; equals the estimate unless growth ran.
     */
    int capacity() {
        return this.outputOffsets.length;
    }

    int outputOffset(int index) {
        return this.outputOffsets[index];
    }

    long packedSpan(int index) {
        return this.packedSpans[index];
    }
}
