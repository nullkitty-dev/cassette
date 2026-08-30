package dev.nullkitty.cassette.ast;

/**
 * An id selector: {@code #main}.
 *
 * <p>Only a hash token whose value is a valid identifier becomes one of these. {@code #336699}
 * is a hash token too, but not an id selector, and a rule whose prelude is one is invalid.
 *
 * @param name       the id without its {@code #}, with escapes resolved
 * @param packedSpan the packed region of source this selector was parsed from, {@code #} included
 */
public record IdSelector(String name, //
                         long packedSpan)
    implements
        SimpleSelector {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public IdSelector(String name, //
                      SourceSpan span) {
        this(name, span.packed());
    }
}
