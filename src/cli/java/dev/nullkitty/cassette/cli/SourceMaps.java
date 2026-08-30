package dev.nullkitty.cassette.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import dev.nullkitty.cassette.serializer.SerializeResult;
import dev.nullkitty.cassette.sourcemap.SourceMap;

/**
 * The three things a source map needs that the library does not do.
 *
 * <p>{@code CssSerializer.serializeWithMap} returns a map with {@code file} and {@code sourceRoot}
 * unset and {@code sources} named exactly as the resolver named them, because the library has no
 * filesystem and cannot compute a path from an output file to a source. This is the component that
 * has both paths, so this is where the answer lives. The library declines to rewrite {@code url()}
 * on the same division.
 *
 * <p>It is also where the trailer is appended, which is the one exception to the rule that the tool
 * writes exactly what the serializer returned and adds nothing. The trailer is the one byte that
 * may legitimately differ between a development build and a production build of the same
 * stylesheet, so it has to look like this tool linking two files it is creating, not like the
 * serializer behaving differently depending on a flag.
 */
final class SourceMaps {

    /**
     * What a {@code data:} URI has to say for a browser to read the map out of it.
     */
    private static final String DATA_URI_PREFIX = "data:application/json;charset=utf-8;base64,";

    /**
     * The id {@code Cli} gives standard input, which names no file anything can open.
     */
    private static final String STDIN = SourceIds.STDIN;

    /**
     * The CSS with its trailer, and the map to write beside it.
     *
     * <p>The map rather than its JSON, so that writing it can stream. Rendering it here would
     * build the whole thing, mostly {@code sourcesContent}, for {@code Output} to copy into a
     * file, which is what {@code SourceMap.writeJson} exists to avoid.
     *
     * @param css what to write where the stylesheet goes
     * @param map the sidecar, or {@code null} when there is none to write, no map at all, or one
     *            inlined into the trailer above
     */
    record Attached(String css, //
                    SourceMap map) {
    }

    /**
     * @param mapFile where the sidecar will be written, or {@code null} for an inlined map
     */
    static Path fileFor(Path output) {
        return output == null ? null : output.resolveSibling(output.getFileName() + ".map");
    }

    /**
     * Completes a map and links it to the stylesheet.
     *
     * @param result   what the serializer returned
     * @param options  the invocation
     * @param output   where the CSS goes, or {@code null} for standard output
     * @return the CSS to write, and the JSON to write beside it
     */
    static Attached attach(SerializeResult result, //
                           Options options,
                           Path output) {
        Path mapFile = options.sourceMap() == SourceMapMode.FILE ? fileFor(output) : null;

        SourceMap generated = result.sourceMap();
        SourceMap complete = new SourceMap(output == null ? null : output.getFileName().toString(),
                                           null,
                                           relativize(generated.sources(), mapFile == null ? output : mapFile),
                                           options.sourceMapContent() ? generated.sourcesContent() : null,
                                           generated.mappings());

        // Only the inline mode needs the JSON as a String, because base64 has to see all of it.
        // The sidecar is streamed instead, so it is never built here.
        String url =
            options.sourceMapUrl() != null ? options.sourceMapUrl()
                                           : options.sourceMap() == SourceMapMode.INLINE ? dataUri(complete)
                                                                                         : mapFile.getFileName()
                                                                                                  .toString();

        return new Attached(withTrailer(result.css(), url), mapFile == null ? null : complete);
    }

    /**
     * Appends the trailer, on a line of its own.
     *
     * <p>The newline before it is added only when the output does not already end in one, so
     * a formatted stylesheet and a minified one, which ends in {@code }} and nothing else,
     * both come out with the trailer as the last line and no blank line before it.
     */
    private static String withTrailer(String css, //
                                      String url) {
        String separator = css.isEmpty() || css.endsWith("\n") ? "" : "\n";

        return css + separator + SourceMap.trailerFor(url) + "\n";
    }

    private static String dataUri(SourceMap map) {
        return DATA_URI_PREFIX + Base64.getEncoder().encodeToString(map.toJson().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Source ids as paths relative to the directory the map will sit in.
     *
     * <p>Which is what the format means by {@code sources}: a consumer resolves each entry
     * against the map's own location. An id that names no file, standard input's, is left
     * exactly as it is, and so is one on another filesystem root, where {@code relativize}
     * either throws or produces something longer and less readable than the absolute path.
     *
     * <p>And so is everything, when the CSS is going to a pipe. There is then no map file and no
     * output file, so there is no directory to be relative <em>to</em>. The consumer resolves
     * against wherever the stylesheet ends up, which this cannot know. The ids stay as the command
     * line named them, and {@code --source-map-url} is the control for anyone who needs more.
     */
    private static List<String> relativize(List<String> sources, Path base) {
        Path directory = base == null ? null : base.toAbsolutePath().getParent();
        if (directory == null) {
            return sources;
        }

        Path from = canonical(directory);
        List<String> relative = new ArrayList<>(sources.size());
        for (String source : sources) {
            relative.add(relativize(source, from));
        }

        return relative;
    }

    private static String relativize(String source, Path directory) {
        if (source.equals(STDIN)) {
            return source;
        }

        try {
            Path path = canonical(Path.of(source).toAbsolutePath());

            // Separators stay '/' whatever the platform uses: a source map is consumed by a
            // browser, and a backslash there is an escape rather than a directory.
            return directory.relativize(path).toString().replace('\\', '/');
        }
        catch (IllegalArgumentException notAPath) {
            // InvalidPathException is one of these, and so is a relativize across roots.
            return source;
        }
    }

    /**
     * A path with every symlink on it resolved, as far as the filesystem can say.
     *
     * <p>Both sides of the relativize have to agree about what a directory is called, and without
     * this they do not. A bundled source's id has already been through {@link SourceIds}, which
     * calls {@code toRealPath}. The output path has not, because it is whatever was typed after
     * {@code -o}. Where the two spellings differ, as on macOS, which puts every temporary directory
     * under {@code /var}, a symlink to {@code /private/var}, relativizing one against the other
     * walks up to the filesystem root and back down. That produces a {@code sources} entry with a
     * dozen {@code ../} in it that resolves correctly and cannot be read. Canonicalizing both is
     * what makes them comparable.
     *
     * <p>The output directory usually does not exist yet, since it is created when the file is
     * written, so this resolves the longest existing prefix and re-attaches the rest, rather
     * than giving up the moment {@code toRealPath} fails.
     */
    private static Path canonical(Path path) {
        Path existing = path;
        Path trailing = null;

        while (existing != null) {
            try {
                Path real = existing.toRealPath();
                return trailing == null ? real : real.resolve(trailing);
            }
            catch (IOException notThereYet) {
                Path name = existing.getFileName();
                trailing = name == null ? trailing : (trailing == null ? name : name.resolve(trailing));
                existing = existing.getParent();
            }
        }

        return path.normalize();
    }

    private SourceMaps() {
        // utility class
    }

}
