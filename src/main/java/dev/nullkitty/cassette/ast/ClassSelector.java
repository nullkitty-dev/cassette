package dev.nullkitty.cassette.ast;

/**
 * A class selector: {@code .card}.
 *
 * @param name       the class name without its {@code .}, with escapes resolved
 * @param packedSpan the packed region of source this selector was parsed from, {@code .} included
 */
public record ClassSelector(String name, //
                            long packedSpan)
    implements
        SimpleSelector {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public ClassSelector(String name, //
                         SourceSpan span) {
        this(name, span.packed());
    }
}
