package dev.nullkitty.cassette.ast;

/**
 * An identifier: {@code red}, {@code solid}, {@code -webkit-box}.
 *
 * @param value      the identifier with escapes resolved and ASCII case preserved
 * @param packedSpan the packed region of source this token was parsed from
 */
public record IdentToken(String value, //
                         long packedSpan)
    implements
        PreservedToken {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public IdentToken(String value, //
                      SourceSpan span) {
        this(value, span.packed());
    }
}
