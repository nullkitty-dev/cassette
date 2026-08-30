package dev.nullkitty.cassette.sourcemap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.nullkitty.cassette.lexer.CodePoints;

/**
 * A Source Map, revision 3.
 *
 * <p>The model of the format and nothing else. {@code CssSerializer.serializeWithMap} produces
 * one, and a caller either writes it beside the output or inlines it into a {@code data:} URI.
 *
 * <pre>{@code
 * SerializeResult result = CssSerializer.serializeWithMap(sheet, options, resolver);
 * Files.writeString(out, result.css() + "\n" + SourceMap.trailerFor("out.css.map"));
 * Files.writeString(mapFile, result.sourceMap().toJson());
 * }</pre>
 *
 * <p>{@code file} and {@code sourceRoot} belong to the caller and are null here. The library has
 * no filesystem and cannot compute a path from an output file to a source, so source ids go into
 * {@link #sources} exactly as the resolver named them. Whatever knows both paths, which the CLI
 * does, builds a new record with the fields filled in and the ids relativized.
 *
 * <p>{@code names} is always empty. The array exists for identifier renaming in minified
 * JavaScript, and cassette renames nothing: the optimizations rewrite values, and lowercasing a
 * name changes a spelling the map still points at. It is written as {@code []} rather than
 * omitted, so an absent array cannot be read as an unfinished one.
 *
 * @param file           what the generated file is called, or null to omit it
 * @param sourceRoot     a prefix for every entry in {@code sources}, or null to omit it
 * @param sources        what each mapping's source index refers to, in first-seen order
 * @param sourcesContent the full text of each entry in {@code sources}, or null to omit it,
 *                       which is what makes a map that carries no content, and it is the
 *                       dominant cost of one that does
 * @param mappings       the encoded segments
 * @see <a href="https://tc39.es/ecma426/#sec-source-map-format">Source Map (ECMA-426) §9 Source map
 *      format</a>
 */
public record SourceMap(String file, //
                        String sourceRoot,
                        List<String> sources,
                        List<String> sourcesContent,
                        String mappings) {

    /**
     * The only revision this writes, and the only one it claims.
     */
    private static final int VERSION = 3;

    /**
     * Copies the lists so the record is genuinely immutable.
     *
     * @throws NullPointerException     if {@code sources} or {@code mappings} is null, or any
     *                                  element of either list is
     * @throws IllegalArgumentException if {@code sourcesContent} is present and does not have
     *                                  one entry per source
     */
    public SourceMap {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(mappings, "mappings");

        sources = List.copyOf(sources);

        if (sourcesContent != null) {
            if (sourcesContent.size() != sources.size()) {
                throw new IllegalArgumentException("sourcesContent has "
                                                   + sourcesContent.size()
                                                   + " entries for "
                                                   + sources.size()
                                                   + " sources; it is indexed by the same index and must match");
            }

            sourcesContent = List.copyOf(sourcesContent);
        }
    }

    /**
     * The map as JSON, UTF-8 when encoded.
     *
     * @return one object, with the members in the order the specification lists them
     */
    public String toJson() {
        StringBuilder out = new StringBuilder(estimateLength());
        try {
            writeJson(out);
        }
        catch (IOException impossible) {
            // StringBuilder implements Appendable and never throws it.
            throw new AssertionError(impossible);
        }

        return out.toString();
    }

    /**
     * Writes the map as JSON, without building a {@code String} of it first.
     *
     * <p>Prefer this. A map's JSON is mostly {@code sourcesContent}, so {@link #toJson} on a 3.6 MB
     * stylesheet materializes about 4.4 MB only for the caller to copy it somewhere. That is 2.2×
     * the output in allocation, a buffer plus the string copied out of it, and a caller writing the
     * map to a file or a stream wants neither.
     *
     * <pre>{@code
     * try (Writer file = Files.newBufferedWriter(mapPath, StandardCharsets.UTF_8)) {
     *     result.sourceMap().writeJson(file);
     * }
     * }</pre>
     *
     * <p>Members are written in the order the specification lists them, and the ones this map
     * does not carry are skipped, so a map built with no {@code file} and no
     * {@code sourcesContent} writes neither key.
     *
     * @param out where to write it; UTF-8 when it is encoded, which a {@code Writer} decides and
     *            this does not
     * @throws IOException          if {@code out} does
     * @throws NullPointerException if {@code out} is null
     */
    public void writeJson(Appendable out) throws IOException {
        Objects.requireNonNull(out, "out");

        new Json(out).open() //
                     .member("version", VERSION) //
                     .member("file", this.file) //
                     .member("sourceRoot", this.sourceRoot) //
                     .member("sources", this.sources) //
                     .member("sourcesContent", this.sourcesContent) //
                     .member("names", List.of()).member("mappings", this.mappings) //
                     .close();
    }

    /**
     * How long the JSON will be, over rather than under.
     *
     * <p>Undershooting a multi-megabyte buffer costs a doubling plus a copy of everything written
     * so far, so this overshoots by a few percent and discards the spare capacity instead.
     *
     * <p>Escaping is what makes the estimate more than a sum of lengths. The mappings string cannot
     * expand, since base64, {@code ;} and {@code ,} have nothing to escape, but source
     * <em>content</em> can: every newline becomes {@code \n}, two characters for one, and a
     * tab-indented stylesheet with quoted content reaches 19%.
     *
     * <p>Package-private so the sizing test can assert it covers a realistic map. Comparing
     * capacity against length after the fact cannot fail, since growth raises the first above the
     * second by construction.
     */
    int estimateLength() {
        // The keys, the version, the empty names array, and the punctuation between members.
        int estimate = MEMBER_OVERHEAD + this.mappings.length();
        for (String source : this.sources) {
            estimate += source.length() + QUOTES_AND_COMMA;
        }

        if (this.file != null) {
            estimate += this.file.length() + QUOTES_AND_COMMA;
        }

        if (this.sourceRoot != null) {
            estimate += this.sourceRoot.length() + QUOTES_AND_COMMA;
        }

        if (this.sourcesContent != null) {
            for (String content : this.sourcesContent) {
                estimate += content.length() + content.length() / CONTENT_ESCAPE_HEADROOM + QUOTES_AND_COMMA;
            }
        }

        return estimate;
    }

    /**
     * The fixed part: every key, {@code "version":3}, {@code "names":[]} and the braces.
     */
    private static final int MEMBER_OVERHEAD = 256;

    /**
     * A string member's own quotes and its separator.
     */
    private static final int QUOTES_AND_COMMA = 8;

    /**
     * A quarter of the content, against the characters JSON has to escape.
     *
     * <p>Newlines alone are 4–5% of formatted CSS, and a stylesheet indented with tabs and
     * carrying {@code content: "…"} strings reaches 19%: four newlines, two quotes and a tab in
     * every 37 characters. A quarter covers that.
     *
     * <p>Sized generously, because the two directions are not symmetric. Overshooting by a quarter
     * costs a quarter of one buffer that is discarded whole, while undershooting by anything costs
     * a doubling <em>plus</em> a copy of everything written so far. The corpus overshoots by about
     * 15% under this.
     */
    private static final int CONTENT_ESCAPE_HEADROOM = 4;

    /**
     * The comment that points a stylesheet at its map.
     *
     * <p>The spelling lives here so there is one of it. Appending it belongs to whatever creates
     * both files: a serializer would have to be told the map's name, and would make the one byte
     * that may legitimately differ between a development build and a production build look like a
     * serializer setting.
     *
     * @param url what the trailer names, a file name beside the output, or a {@code data:} URI
     * @return the comment text, with no trailing newline
     * @throws NullPointerException if {@code url} is null
     */
    public static String trailerFor(String url) {
        Objects.requireNonNull(url, "url");
        return "/*# sourceMappingURL=" + url + " */";
    }

    /**
     * Whether a comment's body is a {@code sourceMappingURL} annotation.
     *
     * <p>The inverse of {@link #trailerFor}, so that one place decides what the annotation looks
     * like. Its two callers, the transform that removes a stale annotation and the warning about
     * one that survived, would otherwise drift apart.
     *
     * <p>Takes the body rather than the whole comment: {@code Comment.text} is what sits between
     * the delimiters, so this is given {@code # sourceMappingURL=a.map } and never the
     * {@code /*}.
     *
     * <p>Both the {@code #} marker and the older {@code @} are accepted, and the name is matched
     * case-sensitively, as a tool honouring the annotation does. Whitespace is CSS whitespace, not
     * {@link Character#isWhitespace}: a comment body has been through §3.3 preprocessing, so only
     * a space, a tab or a newline can reach this, and accepting U+2028 would claim an annotation
     * where no tool sees one.
     *
     * @param commentText the body of a comment
     * @return whether a tool would read it as pointing at a map
     * @throws NullPointerException if {@code commentText} is null
     * @see <a href="https://tc39.es/ecma426/#sec-linking-inline">Source Map (ECMA-426) §11.1.2 Linking
     *      through inline annotations</a>
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-preprocessing">CSS Syntax Level 3 §3.3</a>
     */
    public static boolean isTrailer(CharSequence commentText) {
        Objects.requireNonNull(commentText, "commentText");

        int at = skipWhitespace(commentText, 0);

        if (at >= commentText.length() || (commentText.charAt(at) != '#' && commentText.charAt(at) != '@')) {
            return false;
        }

        at = skipWhitespace(commentText, at + 1);

        return commentText.length() - at >= ANNOTATION.length()
               && commentText.subSequence(at, at + ANNOTATION.length()).toString().contentEquals(ANNOTATION);
    }

    private static int skipWhitespace(CharSequence text, int from) {
        int at = from;
        while (at < text.length() && CodePoints.isWhitespace(text.charAt(at))) {
            at++;
        }

        return at;
    }

    /**
     * The name a tool looks for, spelled once.
     */
    private static final String ANNOTATION = "sourceMappingURL";

    /**
     * @return a builder that interns sources and encodes segments as they arrive
     */
    public static Builder builder() {
        return new Builder(0);
    }

    /**
     * @param expectedMappings roughly how many mappings will be added, which sizes the encoded
     *                         buffer; being wrong costs a copy and nothing else
     * @return a builder
     */
    public static Builder builder(int expectedMappings) {
        return new Builder(expectedMappings);
    }

    /**
     * Collects sources and segments, in the order a generator walks its output.
     *
     * <p>Segments are delta-encoded against the one before, which is why this takes them in order
     * and cannot take them back. Undoing a segment means re-encoding every one after it, so
     * whatever needs to change its mind does so before it gets here.
     */
    public static final class Builder {

        /**
         * Roughly what one encoded segment costs, for sizing only.
         */
        private static final int CHARACTERS_PER_MAPPING = 10;

        private final List<String> sources = new ArrayList<>();

        private final List<String> sourcesContent = new ArrayList<>();

        private final Map<String, Integer> indexes = new HashMap<>();

        private final StringBuilder encoded;

        private int previousGeneratedLine;

        private int previousGeneratedColumn;

        private int previousSourceIndex;

        private int previousSourceLine;

        private int previousSourceColumn;

        private boolean firstOnLine = true;

        private Builder(int expectedMappings) {
            this.encoded = new StringBuilder(Math.max(64, expectedMappings * CHARACTERS_PER_MAPPING));
        }

        /**
         * Interns one source, so mappings can name it by index.
         *
         * <p>First-seen order, which is the order a generator meets them and is therefore the
         * order they appear in {@link SourceMap#sources}. Calling this twice for the same id
         * returns the same index and keeps the first content.
         *
         * @param sourceId what the source is called
         * @param content  its full text, which is what {@code sourcesContent} carries
         * @return the index to pass to {@link #mapping}
         * @throws NullPointerException if either argument is null
         */
        public int source(String sourceId, CharSequence content) {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(content, "content");

            Integer existing = this.indexes.get(sourceId);
            if (existing != null) {
                return existing;
            }

            int index = this.sources.size();
            this.sources.add(sourceId);
            this.sourcesContent.add(content.toString());
            this.indexes.put(sourceId, index);

            return index;
        }

        /**
         * Records one segment.
         *
         * <p>Positions are 0-based, both ends, which is what the format says and what
         * {@code LineIndex} produces.
         *
         * @param generatedLine   line in the output
         * @param generatedColumn column in the output
         * @param sourceIndex     what {@link #source} returned
         * @param sourceLine      line within that source
         * @param sourceColumn    column within that source
         * @return this builder
         * @throws IllegalArgumentException if {@code generatedLine} is before the last one
         *                                  recorded, since segments cannot be re-encoded
         */
        public Builder mapping(int generatedLine,
                               int generatedColumn,
                               int sourceIndex,
                               int sourceLine,
                               int sourceColumn) {
            if (generatedLine < this.previousGeneratedLine) {
                throw new IllegalArgumentException("generated line "
                                                   + generatedLine
                                                   + " is before "
                                                   + this.previousGeneratedLine
                                                   + "; segments are delta-encoded and must arrive in output order");
            }

            while (this.previousGeneratedLine < generatedLine) {
                this.encoded.append(';');
                this.previousGeneratedLine++;

                // The generated column is the one field that is absolute per line rather than
                // cumulative across the whole map.
                this.previousGeneratedColumn = 0;
                this.firstOnLine = true;
            }

            if (!this.firstOnLine) {
                this.encoded.append(',');
            }

            this.firstOnLine = false;

            Vlq.encode(this.encoded, generatedColumn - this.previousGeneratedColumn);
            Vlq.encode(this.encoded, sourceIndex - this.previousSourceIndex);
            Vlq.encode(this.encoded, sourceLine - this.previousSourceLine);
            Vlq.encode(this.encoded, sourceColumn - this.previousSourceColumn);

            this.previousGeneratedColumn = generatedColumn;
            this.previousSourceIndex = sourceIndex;
            this.previousSourceLine = sourceLine;
            this.previousSourceColumn = sourceColumn;

            return this;
        }

        /**
         * @return the map, with {@code file} and {@code sourceRoot} unset
         */
        public SourceMap build() {
            return new SourceMap(null, null, this.sources, this.sourcesContent, this.encoded.toString());
        }
    }
}
