package dev.nullkitty.cassette.diagnostics;

import java.util.Objects;
import java.util.Optional;

import dev.nullkitty.cassette.ast.SourceSpan;

/**
 * Resolves a {@link SourceSpan} back to the source it came from and the offset it sits at.
 *
 * <p>A span is a pair of offsets and nothing else, so it does not know which text it indexes. For
 * a tree from one parse there is no ambiguity, and {@link #of} is the whole implementation. For a
 * tree assembled from several sources the answer is what lets a diagnostic name a file.
 *
 * <p>Lines and columns are absent. Counting them wants a cached index per source, which is
 * {@link LineIndex} and is not this interface's to own, since a resolver answers per span and has
 * nowhere to keep one. What a caller gets here is enough to build one: the source's identity, its
 * text, and where in that text the span begins.
 *
 * @see SourceSpan#text(CharSequence)
 */
@FunctionalInterface
public interface SourceResolver {

    /**
     * Locates a span.
     *
     * @param span a span from the tree this resolver describes
     * @return where it came from
     * @throws IndexOutOfBoundsException if the span does not lie inside this resolver's text
     */
    Location locate(SourceSpan span);

    /**
     * Locates a span, or reports that it cannot be located.
     *
     * <p>The non-throwing form, for a caller walking a whole tree rather than reporting one
     * diagnostic. Not every span in a tree resolves. A bundler synthesizing a wrapper around an
     * imported sheet that imported others produces a node whose span covers the whole subtree and
     * therefore came from no one file. A tree walk meets that on the first nested import, and an
     * exception is the wrong shape for something so ordinary.
     *
     * <p>The default catches, which is correct for any implementation and cheap for none. An
     * implementation that already computes the range check should override. See
     * {@code SourceIndex} and {@link #of}.
     *
     * <p>The cost is the {@code try} rather than the throw, which only happens on a span that
     * does not resolve. An allocation inside a {@code try} is not scalar-replaced, so the
     * {@link Location} reaches the heap on every call, including the ones that succeed: for a
     * caller walking one span per mapping, 48 of the 64 bytes per mapping a source map
     * allocates.
     *
     * @param span a span from the tree this resolver describes
     * @return where it came from, or empty if this resolver cannot say
     */
    default Optional<Location> tryLocate(SourceSpan span) {
        try {
            return Optional.of(locate(span));
        }
        catch (IndexOutOfBoundsException unresolvable) {
            return Optional.empty();
        }
    }

    /**
     * A resolver for a tree parsed from one source, which is every tree
     * {@code CssParser.parse} produces.
     *
     * <p>Overrides {@link #tryLocate} rather than inheriting it, which is why this is an
     * anonymous class rather than a lambda. The default form wraps the {@link Location} in a
     * {@code try}, which puts it on the heap on every call; the range check below is the same
     * question the record's own constructor asks, answered before allocating instead of by
     * catching afterwards.
     *
     * @param sourceId what to call it in a diagnostic, a file name, a URL, anything the
     *                 caller recognizes
     * @param text     the decoded text the tree was parsed from, as
     *                 {@code CssParser.decode} returns it
     * @return a resolver whose every answer names {@code sourceId}
     * @throws NullPointerException if either argument is {@code null}
     */
    static SourceResolver of(String sourceId, //
                             CharSequence text) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(text, "text");

        return new SourceResolver() {

            @Override
            public Location locate(SourceSpan span) {
                return new Location(sourceId, text, span.start(), span.length());
            }

            @Override
            public Optional<Location> tryLocate(SourceSpan span) {
                // Exactly what Objects.checkFromIndexSize asks inside Location, minus the two
                // tests SourceSpan already guarantees: start and length are never negative. The
                // subtraction cannot overflow for the same reason.
                return span.length() > text.length() - span.start() ? Optional.empty() : Optional.of(locate(span));
            }
        };
    }

    /**
     * Where a span came from.
     *
     * <p>{@code offset} is relative to {@code sourceText}, not to whatever larger space the
     * span itself was expressed in. For a single source the two coincide; for a bundled tree
     * they do not, and this is the component that makes the difference invisible to whoever is
     * rendering.
     *
     * @param sourceId   what to call the source
     * @param sourceText the whole decoded text of that source
     * @param offset     index into {@code sourceText} where the span begins
     * @param length     how many characters it covers
     */
    record Location(String sourceId, //
                    CharSequence sourceText,
                    int offset,
                    int length) {

        /**
         * Checks that the offset lands in the text.
         *
         * @throws NullPointerException      if {@code sourceId} or {@code sourceText} is null
         * @throws IndexOutOfBoundsException if {@code [offset, offset + length)} is not within
         *                                   {@code sourceText}
         */
        public Location {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(sourceText, "sourceText");
            Objects.checkFromIndexSize(offset, length, sourceText.length());
        }

        /**
         * The text this location covers.
         *
         * @return the spanned characters
         */
        public CharSequence text() {
            return this.sourceText.subSequence(this.offset, this.offset + this.length);
        }
    }
}
