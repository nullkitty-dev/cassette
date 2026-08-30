package dev.nullkitty.cassette.ast;

/**
 * A qualified rule or an at-rule.
 *
 * <p>The split between {@link AtRule} and {@link ConditionalGroupRule} is the one place
 * this AST knows something CSS Syntax's own grammar does not. Syntax Level 3 treats every
 * at-rule identically, a name, a prelude, and an optional block of who-knows-what, and so
 * does {@link AtRule}. But {@code @media}, {@code @supports}, {@code @container} and
 * {@code @layer} can contain nested style rules, and flattening has to recurse into them,
 * so that distinction is encoded in the type rather than rediscovered by inspecting block
 * contents at transform time.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-at-rule">CSS Syntax Level 3 §5.4.2 Consume
 *      an at-rule</a>
 */
public sealed interface Rule extends Node
    permits //
        StyleRule,
        AtRule,
        ConditionalGroupRule {
}
