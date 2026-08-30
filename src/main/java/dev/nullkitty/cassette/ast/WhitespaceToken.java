package dev.nullkitty.cassette.ast;

/**
 * A run of whitespace inside a value or prelude.
 *
 * <p>Only whitespace that separates component values survives into the tree; the parser
 * trims it at the edges of declaration values and preludes, where it means nothing. What is
 * kept means something, {@code 1px solid} is two values, and the gap is how anyone knows.
 *
 * <p>The exact characters are not stored. Passthrough serialization reformats whitespace to
 * a consistent style rather than reproducing the source byte-for-byte, so nothing would
 * read them.
 *
 * @param packedSpan the packed region of source this token was parsed from
 */
public record WhitespaceToken(long packedSpan) implements PreservedToken {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public WhitespaceToken(SourceSpan span) {
        this(span.packed());
    }
}
