package dev.nullkitty.cassette.ast;

/**
 * An unquoted {@code url(...)}.
 *
 * <p>A quoted one is not this: {@code url("x.png")} tokenizes as a function plus a string,
 * and so arrives as a {@link FunctionValue}. The contents are never interpreted, no
 * resolution, no validation, no normalization, which is a stated non-goal.
 *
 * @param value      the url's contents with escapes resolved, surrounding whitespace stripped
 * @param terminated whether a closing {@code )} was found before end of input
 * @param packedSpan the packed region of source this token was parsed from, {@code url(} included
 */
public record UrlToken(String value, //
                       boolean terminated,
                       long packedSpan)
    implements
        PreservedToken {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public UrlToken(String value, //
                    boolean terminated,
                    SourceSpan span) {
        this(value, terminated, span.packed());
    }
}
