package dev.nullkitty.cassette.serializer;

import java.util.Objects;

import dev.nullkitty.cassette.sourcemap.SourceMap;

/**
 * What {@link CssSerializer#serializeWithMap} hands back: the CSS, and where every mapped
 * construct in it came from.
 *
 * <p>A result record here, where {@code serialize} returns a bare {@code String}. The objection to
 * a result record there was that a second return type taxes every caller with diagnostics almost
 * none of them have. A caller reaching for this method has asked for a second output, so nobody is
 * taxed who did not ask, and the {@code serialize} overloads keep their return type.
 *
 * @param css       the stylesheet, byte for byte what {@code serialize} would have returned for
 *                  the same tree and options
 * @param sourceMap the map, with {@code file} and {@code sourceRoot} unset
 */
public record SerializeResult(String css, //
                              SourceMap sourceMap) {

    /**
     * @throws NullPointerException if either argument is null
     */
    public SerializeResult {
        Objects.requireNonNull(css, "css");
        Objects.requireNonNull(sourceMap, "sourceMap");
    }
}
