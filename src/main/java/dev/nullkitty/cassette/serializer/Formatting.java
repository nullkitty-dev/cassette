package dev.nullkitty.cassette.serializer;

/**
 * How much whitespace the output carries.
 *
 * <p>Neither mode changes what the stylesheet means. Semantic optimizations, shortening
 * colors, dropping zero units, lowercasing names, are opt-in transforms run over the tree
 * before serialization; see {@link Optimizer}.
 */
public enum Formatting {

    /**
     * Indented, one declaration per line, comments kept.
     *
     * <p>A reformatter, not a reproducer: the output is consistently styled rather than
     * byte-identical to the source. Combined with {@link NestingMode#PRESERVE} and the
     * diagnostics the parser already returns; this is a CSS formatter.
     */
    PRETTY,

    /**
     * Whitespace and comments stripped, separators dropped wherever the grammar allows.
     *
     * <p>Whitespace that separates two component values is kept, because {@code 1px solid}
     * means something the concatenation does not.
     */
    MINIFIED
}
