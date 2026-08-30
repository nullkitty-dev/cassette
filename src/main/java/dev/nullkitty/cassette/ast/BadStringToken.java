package dev.nullkitty.cassette.ast;

/**
 * CSS Syntax's bad-string-token: a string interrupted by a newline.
 *
 * <p>It carries no value. The spec gives a bad-string-token no contents, and any construct
 * containing one is invalid, so preserving the partial text would only invite a serializer to emit
 * something the author did not write.
 *
 * @param packedSpan the packed region of source this token was parsed from
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-string-token">CSS Syntax Level 3 §4.3.5
 *      Consume a string token</a>
 */
public record BadStringToken(long packedSpan) implements PreservedToken {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public BadStringToken(SourceSpan span) {
        this(span.packed());
    }
}
