package dev.nullkitty.cassette.ast;

/**
 * A half-open region {@code [start, start + length)} of the decoded source buffer.
 *
 * <p>Offsets are indices into the {@code char[]} the lexer decodes upfront, never byte offsets into
 * the original input. The two differ for any non-ASCII or BOM-prefixed source. Error recovery
 * depends on these, since it has to know exactly where a malformed construct started and ended.
 *
 * <h2>Packing</h2>
 *
 * <p>Nodes do not hold a {@code SourceSpan}. They hold the {@code long} this class
 * {@linkplain #pack packs} one into, and {@link Node#span()} builds the record back on demand. A
 * span object is 24 bytes reached through a reference where the packed form is 8 bytes inside the
 * node itself, and there is one span per node: unpacked, that is around 25% of what a parsed tree
 * retains.
 *
 * <p>The trade is that {@code span()} allocates. That is affordable because nothing inside
 * this library reads a node's start or length: every use is a pass-through, copying one
 * node's span onto another, and those move packed. Code that walks a large tree reading
 * offsets should hold the {@code long} and use {@link #startOf} and {@link #lengthOf}
 * rather than calling {@code span()} per node.
 *
 * @param start  index of the first character, zero-based
 * @param length number of characters, never negative
 */
public record SourceSpan(int start, int length) {

    /**
     * A span of no characters at offset zero, for synthesized nodes with no source.
     */
    public static final SourceSpan NONE = new SourceSpan(0, 0);

    /**
     * {@link #NONE} in packed form, for a synthesized node's constructor.
     */
    public static final long NONE_PACKED = 0L;

    /**
     * Rejects negative offsets, which only ever arise from arithmetic bugs in the lexer.
     *
     * @throws IllegalArgumentException if {@code start} or {@code length} is negative
     */
    public SourceSpan {
        if (start < 0) {
            throw new IllegalArgumentException("start must not be negative: " + start);
        }

        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative: " + length);
        }
    }

    /**
     * Packs a region into the {@code long} an AST node stores, without building a span for
     * it.
     *
     * <p>The start goes in the high half and the length in the low half. Both are
     * non-negative, so the high half never carries a sign bit into the shift and the
     * encoding is order-preserving on start; a packed span sorts by source position.
     *
     * @param start  index of the first character, zero-based
     * @param length number of characters
     * @return the packed form
     * @throws IllegalArgumentException if {@code start} or {@code length} is negative
     */
    public static long pack(int start, int length) {
        if (start < 0) {
            throw new IllegalArgumentException("start must not be negative: " + start);
        }

        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative: " + length);
        }

        return ((long) start << Integer.SIZE) | Integer.toUnsignedLong(length);
    }

    /**
     * Rebuilds a span from its packed form.
     *
     * @param packed a value from {@link #pack} or {@link #packed()}
     * @return the span
     */
    public static SourceSpan unpack(long packed) {
        return new SourceSpan(startOf(packed), lengthOf(packed));
    }

    /**
     * Reads the start out of a packed span without building one.
     *
     * @param packed a value from {@link #pack} or {@link #packed()}
     * @return index of the first character
     */
    public static int startOf(long packed) {
        return (int) (packed >>> Integer.SIZE);
    }

    /**
     * Reads the length out of a packed span without building one.
     *
     * @param packed a value from {@link #pack} or {@link #packed()}
     * @return number of characters
     */
    public static int lengthOf(long packed) {
        return (int) packed;
    }

    /**
     * This span in the form an AST node stores.
     *
     * @return the packed form
     */
    public long packed() {
        return pack(this.start, this.length);
    }

    /**
     * The exclusive upper bound of this span.
     *
     * @return index one past the last character in this span
     */
    public int end() {
        return this.start + this.length;
    }

    /**
     * Whether this span is zero-width, as synthesized nodes and empty blocks are.
     *
     * @return whether this span covers no characters
     */
    public boolean isEmpty() {
        return this.length == 0;
    }

    /**
     * The smallest span covering both {@code this} and {@code other}, including any gap
     * between them.
     *
     * @param other the span to merge with
     * @return the enclosing span
     */
    public SourceSpan union(SourceSpan other) {
        int unionStart = Math.min(this.start, other.start);
        int unionEnd = Math.max(end(), other.end());
        return new SourceSpan(unionStart, unionEnd - unionStart);
    }

    /**
     * Extracts this span's text from the buffer it was created against.
     *
     * <p>Valid only for a tree parsed from a single source. The span carries an offset and no notion
     * of which text it indexes, so passing a buffer it was not created against returns the wrong
     * characters rather than failing. The offsets are still in range, so nothing here can detect the
     * mistake. Nodes from a concatenated or {@code @import}-inlined tree share one logical
     * coordinate space spanning every source, and no single source's buffer is the right argument
     * for any of them. Use the bundle-aware {@code SourceIndex.textOf} for those. A tree from
     * {@code CssParser.parse} is always safe, because there the coordinate space and the buffer are
     * the same thing.
     *
     * @param source the decoded source this span indexes into
     * @return the spanned characters
     */
    public CharSequence text(CharSequence source) {
        return source.subSequence(this.start, end());
    }

    @Override
    public String toString() {
        return "SourceSpan[" + this.start + ", " + end() + ")";
    }
}
