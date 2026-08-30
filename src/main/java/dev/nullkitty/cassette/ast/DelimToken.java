package dev.nullkitty.cassette.ast;

/**
 * A single code point with no more specific meaning: {@code *}, {@code /}, {@code +} in a
 * {@code calc()}, {@code >} in a selector.
 *
 * <p>Stored as a code point rather than a {@code char} because the tokenizer's fallback
 * branch will produce one for any unrecognized character, astral ones included.
 *
 * @param codePoint  the delimiter's code point
 * @param packedSpan the packed region of source this token was parsed from
 */
public record DelimToken(int codePoint, //
                         long packedSpan)
    implements
        PreservedToken {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public DelimToken(int codePoint, //
                      SourceSpan span) {
        this(codePoint, span.packed());
    }

    /**
     * Whether this is a particular delimiter.
     *
     * @param expected the code point to test for
     * @return whether they match
     */
    public boolean is(char expected) {
        return this.codePoint == expected;
    }

    /**
     * The delimiter as text.
     *
     * @return a one- or two-{@code char} string holding the code point
     */
    public String text() {
        return new String(Character.toChars(this.codePoint));
    }
}
