package dev.nullkitty.cassette.ast;

/**
 * A {@code /* ... *}{@code /} comment, kept as a real node.
 *
 * <p>CSS Syntax discards comments at the tokenizer and never mentions them again. This
 * parser keeps them, everywhere they can appear, between rules, between declarations,
 * inside a value, because that is what lets passthrough serialization put them back.
 * Retrofitting comment preservation after the fact means touching the tokenizer,
 * the AST and every parser algorithm at once.
 *
 * @param text       the comment's contents, {@code /*} and {@code *}{@code /} excluded
 * @param terminated whether a closing {@code *}{@code /} was found before end of input
 * @param packedSpan the packed region of source this comment was parsed from, delimiters included
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-comment">CSS Syntax Level 3 §4.3.2 Consume
 *      comments</a>
 */
public record Comment(String text, //
                      boolean terminated,
                      long packedSpan)
    implements
        PreservedToken {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public Comment(String text, //
                   boolean terminated,
                   SourceSpan span) {
        this(text, terminated, span.packed());
    }
}
