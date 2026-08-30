package dev.nullkitty.cassette.bundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.diagnostics.SourceResolver;

/**
 * The map from one coordinate space back to the sources laid out in it.
 *
 * <p>Several sources parsed into one tree share a single space of character offsets: each is
 * given a base, and every span it produces is born shifted by that base rather than rewritten
 * afterwards. This holds the segment table that undoes it, so a span from anywhere in such a
 * tree resolves to the source it came from and the offset it sat at there.
 *
 * <pre>{@code
 * SourceIndex.Builder layout = SourceIndex.builder();
 * List<Stylesheet> trees = new ArrayList<>();
 * for (Path path : paths) {
 *     int base = layout.nextBase();
 *     String text = CssParser.decode(Files.readAllBytes(path), null, base, diagnostics::add);
 *     trees.add(CssParser.parse(text, base).ast());
 *     layout.add(path.toString(), text);
 * }
 * SourceIndex index = layout.build();
 * }</pre>
 *
 * <p>A caller never supplies a base: {@link Builder#nextBase()} hands out the running cursor and
 * {@link Builder#add} advances it by the text's length in <em>characters</em>. A BOM has been
 * stripped and §3.3 has collapsed CRLF, so a base computed from a byte count puts every later
 * span in the wrong source.
 *
 * <p>Segment order is the order the sources were decoded in, which for {@code @import} resolution
 * is depth-first rather than the tree's cascade order.
 *
 * <p>Implements {@link SourceResolver}, so whatever renders a diagnostic takes a bundled tree and
 * a single-source one through the same code. {@code SourceResolver.of} is the degenerate case of
 * this class.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-preprocessing">CSS Syntax Level 3 §3.3</a>
 */
public final class SourceIndex implements SourceResolver {

    private final List<Segment>      segments;
    private final List<CharSequence> texts;
    private final int                length;

    private SourceIndex(List<Segment> segments, //
                        List<CharSequence> texts,
                        int length) {
        this.segments = List.copyOf(segments);
        this.texts = List.copyOf(texts);
        this.length = length;
    }

    /**
     * @return a builder that lays sources out from offset zero
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The sources in this space, in the order they were laid out.
     *
     * @return the segment table
     */
    public List<Segment> segments() {
        return this.segments;
    }

    /**
     * The width of the whole coordinate space.
     *
     * @return the sum of every segment's length
     */
    public int length() {
        return this.length;
    }

    /**
     * Resolves an offset to the source holding it.
     *
     * @param offset an offset in this coordinate space; {@link #length()} itself is allowed,
     *               being where a zero-width span at the very end sits
     * @return which source it came from and where in it
     * @throws IndexOutOfBoundsException if the offset is negative, past the end, or this index
     *                                   holds no sources at all
     */
    public Origin resolve(int offset) {
        int at = segmentFor(offset);
        Segment segment = this.segments.get(at);
        return new Origin(segment.sourceId(), offset - segment.base());
    }

    /**
     * Resolves a span to the source holding it.
     *
     * <p>A span covering more than one source is refused rather than attributed to one. Every span
     * a parse produces lies in a single source, so this arises only for a node the bundler
     * synthesized, such as a group rule wrapping an imported sheet that imported others.
     * {@link Segment#importedFrom()} is the question that has an answer for those.
     *
     * @param span a span from a tree laid out in this space
     * @return which source it came from and where in it
     * @throws IndexOutOfBoundsException if the span lies outside this space, or covers more
     *                                   than one source
     */
    public Origin resolve(SourceSpan span) {
        Segment segment = this.segments.get(segmentFor(span));
        return new Origin(segment.sourceId(), span.start() - segment.base());
    }

    /**
     * The characters a span covers, taken from the source it came from.
     *
     * <p>The bundle-aware form of {@link SourceSpan#text(CharSequence)}, which cannot be used
     * here: it slices whatever text it is handed, and no single source's text is the right one
     * for every span in a tree spanning several. Passing the wrong one returns the wrong
     * characters rather than failing, since the offsets are still in range.
     *
     * @param span a span from a tree laid out in this space
     * @return the spanned characters
     * @throws IndexOutOfBoundsException if the span lies outside this space, or straddles two
     *                                   sources
     */
    public CharSequence textOf(SourceSpan span) {
        return locate(span).text();
    }

    @Override
    public Location locate(SourceSpan span) {
        return locationAt(segmentFor(span), span);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Answered from the range check {@link #locate} already computes, so asking without
     * risking an exception costs nothing over asking with one. Which matters because the caller
     * this exists for is a source-map generator walking every node in a bundled tree, where a
     * synthesized wrapper spanning two sources is ordinary rather than exceptional.
     */
    @Override
    public Optional<Location> tryLocate(SourceSpan span) {
        int at = trySegmentFor(span);
        return at < 0 ? Optional.empty() : Optional.of(locationAt(at, span));
    }

    private Location locationAt(int at, //
                                SourceSpan span) {
        Segment segment = this.segments.get(at);
        return new Location(segment.sourceId(), this.texts.get(at), span.start() - segment.base(), span.length());
    }

    /**
     * The segment holding a whole span, which is one segment or it is an error.
     */
    private int segmentFor(SourceSpan span) {
        int at = segmentFor(span.start());

        Segment segment = this.segments.get(at);
        if (span.end() > segment.base() + segment.length()) {
            throw new IndexOutOfBoundsException(span
                                                + " straddles the end of "
                                                + segment.sourceId()
                                                + ", which covers ["
                                                + segment.base()
                                                + ", "
                                                + (segment.base() + segment.length())
                                                + ")");
        }

        return at;
    }

    /**
     * The same question as {@link #segmentFor(SourceSpan)}, answered with -1 rather than a
     * throw.
     */
    private int trySegmentFor(SourceSpan span) {
        if (this.segments.isEmpty() || span.start() < 0 || span.start() > this.length) {
            return -1;
        }

        int at = segmentFor(span.start());
        Segment segment = this.segments.get(at);

        return span.end() > segment.base() + segment.length() ? -1 : at;
    }

    /**
     * Binary search for the last segment that starts at or before {@code offset}.
     *
     * <p>"Last", not "first", is what decides the one ambiguous case: an empty source is a
     * zero-length segment sharing its base with whatever follows it, and every offset there
     * belongs to the follower. The consequence, stated rather than hidden, is that an empty
     * source's own zero-width span resolves to its neighbour; there is no offset that belongs
     * to a segment covering no characters, so there is no answer that names it.
     */
    private int segmentFor(int offset) {
        if (this.segments.isEmpty()) {
            throw new IndexOutOfBoundsException("this index holds no sources");
        }

        if (offset < 0 || offset > this.length) {
            throw new IndexOutOfBoundsException("offset " + offset + " outside [0, " + this.length + "]");
        }

        int low = 0;
        int high = this.segments.size() - 1;

        while (low < high) {
            int middle = (low + high + 1) >>> 1;

            if (this.segments.get(middle).base() <= offset) {
                low = middle;
            }
            else {
                high = middle - 1;
            }
        }

        return low;
    }

    @Override
    public String toString() {
        return "SourceIndex" + this.segments;
    }

    /**
     * One source's place in the coordinate space.
     *
     * @param base         where this source starts, zero-based
     * @param length       how many characters it covers, decoded and preprocessed
     * @param sourceId     what the source was called
     * @param importedFrom the {@code @import} that pulled this source in, or {@code null} for
     *                     one the caller named directly. Kept beside the layout rather than
     *                     derived from spans, because a synthesized wrapper rule carries the
     *                     span of what it wraps and not of the rule that caused it, so "which
     *                     import produced this" is a lookup here and never a span comparison.
     */
    public record Segment(String sourceId, int base, int length, Origin importedFrom) {

        /**
         * @throws NullPointerException     if {@code sourceId} is null
         * @throws IllegalArgumentException if {@code base} or {@code length} is negative
         */
        public Segment {
            Objects.requireNonNull(sourceId, "sourceId");

            if (base < 0) {
                throw new IllegalArgumentException("base must not be negative: " + base);
            }

            if (length < 0) {
                throw new IllegalArgumentException("length must not be negative: " + length);
            }
        }

        @Override
        public String toString() {
            return this.sourceId + "[" + this.base + ", " + (this.base + this.length) + ")";
        }
    }

    /**
     * Lays sources out end to end, handing each the base it starts at.
     *
     * <p>Not thread-safe, and single-use in the sense that {@link #build()} may be called at
     * any point and the builder keeps going afterwards, laying out is sequential by
     * construction, because a source's base is unknown until everything before it has been
     * measured.
     */
    public static final class Builder {

        private final List<Segment> segments = new ArrayList<>();

        private final List<CharSequence> texts = new ArrayList<>();

        private int cursor;

        private Builder() {
            // SourceIndex.builder()
        }

        /**
         * The base the next source added will start at.
         *
         * <p>Read this <em>before</em> decoding and parsing the source it belongs to: the base
         * has to be threaded through both, and it is known in advance precisely because
         * everything ahead of it has already been measured.
         *
         * @return the running cursor
         */
        public int nextBase() {
            return this.cursor;
        }

        /**
         * Appends a source the caller named directly.
         *
         * @param sourceId what to call it
         * @param text     its decoded, preprocessed text, as {@code CssParser.decode} returns it
         * @return this builder
         * @throws NullPointerException if either argument is null
         */
        public Builder add(String sourceId, //
                           CharSequence text) {
            return add(sourceId, text, null);
        }

        /**
         * Appends a source, recording the {@code @import} that pulled it in.
         *
         * @param sourceId     what to call it
         * @param text         its decoded, preprocessed text
         * @param importedFrom where the {@code @import} that caused it sits, or {@code null}
         * @return this builder
         * @throws NullPointerException     if {@code sourceId} or {@code text} is null
         * @throws IllegalArgumentException if {@code text} still contains a carriage return,
         *                                  which means it has not been through preprocessing
         */
        public Builder add(String sourceId, //
                           CharSequence text,
                           Origin importedFrom) {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(text, "text");

            checkPreprocessed(sourceId, text);

            this.segments.add(new Segment(sourceId, this.cursor, text.length(), importedFrom));
            this.texts.add(text);
            this.cursor += text.length();

            return this;
        }

        /**
         * Rejects text the parser would measure differently from this table.
         *
         * <p>§3.3 collapses CRLF to a single LF, and that is the one preprocessing rule that
         * changes a text's <em>length</em>; the others replace a character with a character.
         * So raw text containing a CRLF is one character longer here than in the buffer the
         * parser spans, every base after it is too large by the number of them, and every span
         * in every later source resolves into the wrong file. Nothing downstream could notice:
         * the offsets stay in range and name a real source.
         *
         * <p>One pass, no allocation, against a failure mode that is otherwise silent and
         * arbitrarily far from its cause. {@code CssParser.decode} returns text that passes.
         *
         * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-preprocessing">CSS Syntax Level 3
         *      §3.3</a>
         */
        private static void checkPreprocessed(String sourceId, CharSequence text) {
            for (int index = 0; index < text.length(); index++) {
                if (text.charAt(index) == '\r') {
                    throw new IllegalArgumentException(sourceId
                                                       + " has a carriage return at "
                                                       + index
                                                       + ", so it has not been preprocessed; add the text "
                                                       + "CssParser.decode returned, whose length is what a span measures");
                }
            }
        }

        /**
         * @return the index over everything added so far
         */
        public SourceIndex build() {
            return new SourceIndex(this.segments, this.texts, this.cursor);
        }
    }
}
