/**
 * Source Map revision 3: the format model, and what writes it.
 *
 * <p>A package of its own rather than three types in {@code serializer}, because Source Map v3
 * is an external format with a future that is not cassette's: {@code ignoreList}, index maps and
 * ongoing standardization all land here and nowhere else. The same argument that gave bundling
 * its own package.
 *
 * <p>{@link dev.nullkitty.cassette.sourcemap.SourceMap} is the whole of the exported surface.
 * The VLQ codec and the JSON writer are package-private: neither is a general-purpose utility,
 * and both exist only to spell this one format.
 *
 * <p>Nothing here reads a map. Chaining an input map, so that a stylesheet cassette processed from
 * a Sass build points at the {@code .scss}, needs a JSON parser and a VLQ decoder, and is a feature
 * of its own.
 *
 * @see <a href="https://tc39.es/ecma426/">ECMA-426, the source map format specification</a>
 */
package dev.nullkitty.cassette.sourcemap;
