/**
 * Recursive-descent parser and the public entry point, {@code CssParser}.
 *
 * <p>Stateless and static-style: parsing never throws for recoverable input, returning a
 * {@code ParseResult} of AST plus diagnostics instead.
 */
package dev.nullkitty.cassette.parser;
