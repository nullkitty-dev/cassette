/**
 * What a stage of the pipeline has to say about its input, and how much it matters.
 *
 * <p>Two types, shared by everything that reports: the parser, which produces most
 * diagnostics, and the serializer, which produces them through an optional sink when it drops
 * a value that has no spelling reading back as itself. Neither depends on the other, and
 * nothing here depends on either, this package sits below both, alongside {@code ast}.
 *
 * <p>It exists rather than these living in {@code parser} because an embedder printing a
 * diagnostic should not have to import a parser to name its type, and much of what it prints
 * comes from the bundler rather than from a parse.
 */
package dev.nullkitty.cassette.diagnostics;
