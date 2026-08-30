/**
 * Immutable AST: sealed interfaces and records for stylesheets, rules, declarations,
 * component values, comments, and the full Selectors Level 4 selector grammar.
 *
 * <p>Every node carries a span and comments are real nodes rather than tokenizer-level
 * trivia. The span is stored as the {@code long} that
 * {@link dev.nullkitty.cassette.ast.SourceSpan#pack} produces, one per node made it the
 * largest single thing a parsed tree retained, and
 * {@link dev.nullkitty.cassette.ast.Node#span()} unpacks it on demand. Every record also
 * takes a {@code SourceSpan} through a convenience constructor, so building a node by hand
 * never needs the packed form.
 *
 * @see <a href="https://www.w3.org/TR/selectors-4/#grammar">Selectors Level 4 §16 Grammar</a>
 */
package dev.nullkitty.cassette.ast;
