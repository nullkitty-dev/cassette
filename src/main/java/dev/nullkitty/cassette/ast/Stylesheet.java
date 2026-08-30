package dev.nullkitty.cassette.ast;

import java.util.List;

/**
 * A whole parsed stylesheet: the root of the tree.
 *
 * <p>{@code children} holds {@link Rule}s and {@link Comment}s in source order. Comments are
 * kept as siblings of the rules they sit between, which is what lets the passthrough
 * serializer put them back where the author wrote them.
 *
 * @param children   the top-level rules and comments, in source order
 * @param packedSpan the packed region of source this stylesheet was parsed from
 */
public record Stylesheet(List<Node> children, //
                         long packedSpan)
    implements
        Node {

    /**
     * Copies {@code children} so the record is genuinely immutable.
     *
     * @throws NullPointerException if any argument or child is {@code null}
     */
    public Stylesheet {
        children = List.copyOf(children);
    }

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public Stylesheet(List<Node> children, //
                      SourceSpan span) {
        this(children, span.packed());
    }

    /**
     * The top-level rules, skipping comments.
     *
     * @return the rules in source order
     */
    public List<Rule> rules() {
        return this.children.stream() //
                            .filter(Rule.class::isInstance) //
                            .map(Rule.class::cast) //
                            .toList();
    }
}
