package dev.nullkitty.cassette.ast;

import java.util.List;

import dev.nullkitty.cassette.text.Ascii;

/**
 * An at-rule whose contents this parser does not interpret: {@code @font-face},
 * {@code @import}, {@code @keyframes}, {@code @page} and everything else not in
 * {@link ConditionalGroupRule}.
 *
 * <p>Both the prelude and the block stay as raw {@link ComponentValue} lists. That is not a
 * shortcut; it is what CSS Syntax Level 3 itself defines, which knows no more about what
 * {@code @font-face} means than this parser does.
 *
 * @param name       the at-keyword without its {@code @}, with escapes resolved and ASCII case preserved
 * @param prelude    everything between the name and the block or semicolon
 * @param block      the block's contents, or {@code null} for a statement at-rule ended by {@code ;}
 * @param packedSpan the packed region of source this rule was parsed from, {@code @} through its end
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-at-rule">CSS Syntax Level 3 §5.4.2 Consume
 *      an at-rule</a>
 */
public record AtRule(String name, //
                     List<ComponentValue> prelude,
                     List<ComponentValue> block,
                     long packedSpan)
    implements
        Rule {

    /**
     * Copies the lists so the record is genuinely immutable; {@code block} stays nullable,
     * because "no block" and "empty block" are different rules.
     *
     * @throws NullPointerException if {@code name}, {@code prelude}, {@code span} or any
     *         list element is {@code null}
     */
    public AtRule {
        prelude = List.copyOf(prelude);
        block = block == null ? null : List.copyOf(block);
    }

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public AtRule(String name,
                  List<ComponentValue> prelude, //
                  List<ComponentValue> block,
                  SourceSpan span) {
        this(name, prelude, block, span.packed());
    }

    /**
     * Whether this at-rule ended at a semicolon rather than a block.
     *
     * @return whether it is a statement at-rule, as {@code @import} and {@code @charset} are
     */
    public boolean isStatement() {
        return this.block == null;
    }

    /**
     * Compares this rule's name to a literal, the ASCII case-insensitive way CSS matches
     * at-keywords.
     *
     * @param expected the lowercase name to compare against
     * @return whether the names match
     */
    public boolean nameIs(String expected) {
        return Ascii.equalsIgnoreCase(this.name, expected);
    }
}
