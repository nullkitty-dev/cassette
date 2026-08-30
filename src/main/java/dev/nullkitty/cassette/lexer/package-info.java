/**
 * Tokenizer: byte-level charset and BOM detection, upfront decode into a single
 * {@code char[]}, escape handling, and the CSS Syntax Module Level 3 token stream.
 *
 * <p>Internal to the module, token spans are {@code (start, length)} pairs into the
 * decoded buffer and are not part of the public surface.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#tokenization">CSS Syntax Level 3 §4 Tokenization</a>
 */
package dev.nullkitty.cassette.lexer;
