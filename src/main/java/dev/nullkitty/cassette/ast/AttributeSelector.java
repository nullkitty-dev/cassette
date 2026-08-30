package dev.nullkitty.cassette.ast;

/**
 * An attribute test: {@code [href]}, {@code [type="text"]}, {@code [lang|="en" i]}.
 *
 * <p>Whether the value was written quoted is not recorded. The grammar accepts either an
 * identifier or a string there, both mean the same thing, and choosing how to emit it is a
 * serializer decision.
 *
 * @param namespace  the namespace prefix, or {@code null} if none was written; see
 *                   {@link TypeSelector} for what the three states mean
 * @param name       the attribute name, with escapes resolved
 * @param matcher    how the value is compared, {@link AttributeMatcher#PRESENT} for a bare test
 * @param value      the value compared against, or {@code null} when {@code matcher} is
 *                   {@link AttributeMatcher#PRESENT}
 * @param caseMode   the {@code i} or {@code s} modifier, if one was written
 * @param packedSpan the packed region of source this selector was parsed from, brackets included
 */
public record AttributeSelector(String namespace, //
                                String name,
                                AttributeMatcher matcher,
                                String value,
                                AttributeCase caseMode,
                                long packedSpan)
    implements
        SimpleSelector {

    /**
     * Rejects the two combinations the grammar cannot produce: a matcher with no value, and
     * a value with no matcher.
     *
     * @throws IllegalArgumentException if {@code matcher} and {@code value} disagree about
     *         whether there is a comparison
     */
    public AttributeSelector {
        if ((matcher == AttributeMatcher.PRESENT) != (value == null)) {
            throw new IllegalArgumentException("matcher " + matcher + " and value " + value + " disagree");
        }
    }

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public AttributeSelector(String namespace,
                             String name,
                             AttributeMatcher matcher,
                             String value,
                             AttributeCase caseMode,
                             SourceSpan span) {
        this(namespace, name, matcher, value, caseMode, span.packed());
    }
}
