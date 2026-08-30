package dev.nullkitty.cassette.ast;

/**
 * A number with a unit: {@code 10px}, {@code 2.5rem}, {@code 90deg}.
 *
 * <p>The unit is not validated. CSS Syntax's grammar admits any identifier there, and
 * deciding that {@code 10foo} is meaningless belongs to a value parser this library does
 * not have.
 *
 * @param rawText     the number as written, unit excluded
 * @param value       the numeric value per CSS Syntax §4.3.13
 * @param unit        the unit identifier with escapes resolved and ASCII case preserved
 * @param hasSign     whether an explicit {@code +} or {@code -} was written
 * @param hasExponent whether the number was written in exponential notation
 * @param packedSpan  the packed region of source this token was parsed from, unit included
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#convert-string-to-number">CSS Syntax Level 3
 *      §4.3.13</a>
 */
public record DimensionToken(String rawText, //
                             double value,
                             String unit,
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
    public DimensionToken(String rawText,
                          double value,
                          String unit,
                          boolean hasSign,
                          boolean hasExponent,
                          SourceSpan span) {
        this(rawText, value, unit, hasSign, hasExponent, span.packed());
    }
}
