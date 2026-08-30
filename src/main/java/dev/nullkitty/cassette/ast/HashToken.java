package dev.nullkitty.cassette.ast;

/**
 * A {@code #}-prefixed token: an ID selector, or a hex colour.
 *
 * <p>{@code id} is the distinction the tokenizer drew and the parser needs: {@code #main}
 * could be an ID selector, {@code #336699} could not, and the difference is whether the
 * value is a valid identifier. Both still reach here as hash tokens; only a selector context
 * turns the first into an {@link IdSelector}.
 *
 * @param value      the text after the {@code #}, with escapes resolved
 * @param id         whether the value is a valid identifier, and so could name an element
 * @param packedSpan the packed region of source this token was parsed from, {@code #} included
 */
public record HashToken(String value, //
                        boolean id,
                        long packedSpan)
    implements
        PreservedToken {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public HashToken(String value, //
                     boolean id,
                     SourceSpan span) {
        this(value, id, span.packed());
    }
}
