package dev.nullkitty.cassette.ast;

/**
 * A quoted string.
 *
 * <p>The original quote character is not kept. Which quote to emit is a serializer decision
 * quote normalization is one of the opt-in minification transforms, so recording the
 * author's choice would only be storing something nothing reads.
 *
 * @param value      the string's contents with escapes resolved, quotes excluded
 * @param terminated whether a closing quote was found before end of input; {@code false} is
 *                   a parse error the spec still recovers from by ending the string at EOF
 * @param packedSpan the packed region of source this token was parsed from, quotes included
 */
public record StringToken(String value, //
                          boolean terminated,
                          long packedSpan)
    implements
        PreservedToken {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public StringToken(String value, //
                       boolean terminated,
                       SourceSpan span) {
        this(value, terminated, span.packed());
    }
}
