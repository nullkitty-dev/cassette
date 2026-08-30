package dev.nullkitty.cassette.ast;

/**
 * A number followed by {@code %}.
 *
 * <p>{@code value} is the number as written, not a fraction: {@code 50%} is {@code 50.0}.
 *
 * @param rawText     the number as written, {@code %} excluded
 * @param value       the numeric value per CSS Syntax §4.3.13
 * @param hasSign     whether an explicit {@code +} or {@code -} was written
 * @param hasExponent whether the number was written in exponential notation
 * @param packedSpan  the packed region of source this token was parsed from, {@code %} included
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#convert-string-to-number">CSS Syntax Level 3
 *      §4.3.13</a>
 */
public record PercentageToken(String rawText, //
                              double value,
                              boolean hasSign,
                              boolean hasExponent,
                              long packedSpan)
    implements
        PreservedToken {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public PercentageToken(String rawText, //
                           double value,
                           boolean hasSign,
                           boolean hasExponent,
                           SourceSpan span) {
        this(rawText, value, hasSign, hasExponent, span.packed());
    }
}
