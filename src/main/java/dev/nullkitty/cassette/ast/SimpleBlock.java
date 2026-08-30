package dev.nullkitty.cassette.ast;

import java.util.List;

/**
 * A balanced bracket pair and its contents: {@code [attr=value]}, {@code (min-width: 0)},
 * <code>{ ... }</code> where the parser had no reason to look inside.
 *
 * <p>The closing bracket is implied by the opening one, and is not stored; the parser only
 * produces a block once it has matched the pair, or once it has hit end of input, and in
 * the second case there is no closing bracket to record. Whether the pair was actually
 * closed is a diagnostic, not a property of the node.
 *
 * @param open       the opening bracket: {@code (}, {@code [} or <code>{</code>
 * @param contents   everything between the brackets, as component values
 * @param packedSpan the packed region of source this block was parsed from, brackets included
 */
public record SimpleBlock(char open, //
                          List<ComponentValue> contents,
                          long packedSpan)
    implements
        ComponentValue {

    /**
     * Copies {@code contents} and rejects an opening bracket that is not one.
     *
     * @throws IllegalArgumentException if {@code open} is not {@code (}, {@code [} or <code>{</code>
     * @throws NullPointerException if {@code contents}, {@code span} or any element is {@code null}
     */
    public SimpleBlock {
        if (open != '(' && open != '[' && open != '{') {
            throw new IllegalArgumentException("not an opening bracket: " + open);
        }

        contents = List.copyOf(contents);
    }

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public SimpleBlock(char open, //
                       List<ComponentValue> contents,
                       SourceSpan span) {
        this(open, contents, span.packed());
    }

    /**
     * The bracket that closes this block.
     *
     * @return {@code )}, {@code ]} or <code>}</code>
     */
    public char close() {
        return switch (this.open) {
            case '(' -> ')';
            case '[' -> ']';
            default -> '}';
        };
    }
}
