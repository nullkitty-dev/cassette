package dev.nullkitty.cassette.ast;

/**
 * A unitless number.
 *
 * <p>The raw text is authoritative and the {@code double} is a derived view, not the other
 * way round. That is what keeps {@code .500}, {@code +5} and {@code 1e2} round-trippable
 * byte-for-byte through the AST while still exposing something a minifier can compare
 * against zero, and it sidesteps committing to {@code double} or {@code BigDecimal} as the
 * source of truth.
 *
 * <p>{@code value} follows CSS Syntax §4.3.13's formula rather than {@code strtod}, so for
 * inputs with many significant digits it can differ from {@link Double#parseDouble} in the
 * last bit. That is the spec's definition, not an approximation of it.
 *
 * @param rawText     the number exactly as written, sign and exponent included
 * @param value       the numeric value per §4.3.13
 * @param hasSign     whether an explicit {@code +} or {@code -} was written
 * @param hasExponent whether the number was written in exponential notation
 * @param packedSpan  the packed region of source this token was parsed from
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#convert-string-to-number">CSS Syntax Level 3
 *      §4.3.13</a>
 */
public record NumberToken(String rawText, //
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
    public NumberToken(String rawText, //
                       double value,
                       boolean hasSign,
                       boolean hasExponent,
                       SourceSpan span) {
        this(rawText, value, hasSign, hasExponent, span.packed());
    }

    /**
     * Whether the number was written without a fractional part or exponent.
     *
     * @return whether it is an integer as CSS defines the term, which is about how it was
     *         written and not about whether {@link #value()} happens to be whole
     */
    public boolean isInteger() {
        return !this.hasExponent && this.rawText.indexOf('.') < 0;
    }
}
