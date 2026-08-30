/**
 * Serialization back to CSS text, plus the two tree transforms that run before it.
 *
 * <p>{@code CssSerializer} writes a tree with the axes {@code SerializerOptions} carries;
 * {@code Flattener} rewrites nesting away and {@code Optimizer} runs the opt-in transforms in
 * {@code Optimizations}. Both transforms are tree in, tree out, and neither modifies its
 * input.
 *
 * <p>Only one thing here claims to minify: {@code Formatting.MINIFIED}, which removes
 * whitespace and comments and nothing that changes what a stylesheet means. Everything that
 * does is a transform the caller enables.
 */
package dev.nullkitty.cassette.serializer;
