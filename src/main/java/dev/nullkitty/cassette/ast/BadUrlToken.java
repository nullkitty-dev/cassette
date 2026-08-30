package dev.nullkitty.cassette.ast;

/**
 * CSS Syntax's bad-url-token: a malformed unquoted url.
 *
 * <p>Valueless for the same reason as {@link BadStringToken}, the spec gives it no
 * contents, and the tokenizer has already consumed through the closing {@code )} as part of
 * §4.3.14's recovery.
 *
 * @param packedSpan the packed region of source this token was parsed from
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-remnants-of-bad-url">CSS Syntax Level 3
 *      §4.3.14</a>
 */
public record BadUrlToken(long packedSpan) implements PreservedToken {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public BadUrlToken(SourceSpan span) {
        this(span.packed());
    }
}
