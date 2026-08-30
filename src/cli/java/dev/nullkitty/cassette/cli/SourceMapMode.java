package dev.nullkitty.cassette.cli;

/**
 * Where a source map goes, if one is generated at all.
 *
 * <p>Three states rather than a boolean and a second flag, because the two that produce a map
 * differ in what they need from the destination: a sidecar has nowhere to go when the CSS is
 * going to a pipe, and an inlined one does not care.
 */
enum SourceMapMode {

    /**
     * No map. The default, and the production path.
     */
    NONE,

    /**
     * A {@code .map} file beside the output, named by a trailer in the CSS.
     */
    FILE,

    /**
     * The whole map, base64-encoded into the trailer as a {@code data:} URI.
     */
    INLINE;

    static SourceMapMode parse(String value) {
        return switch (value) {
            case "none" -> NONE;
            case "file" -> FILE;
            case "inline" -> INLINE;
            default -> null;
        };
    }

    /**
     * @return whether this mode produces a map at all
     */
    boolean generates() {
        return this != NONE;
    }
}
