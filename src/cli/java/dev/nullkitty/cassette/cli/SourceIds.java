package dev.nullkitty.cassette.cli;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * What a bundled source is called, in diagnostics and in banners.
 *
 * <p>One file must have exactly one id, because the id is what cycle detection compares. A file
 * reached two ways, as {@code a.css} on the command line and {@code ./a.css} through an
 * {@code @import}, would be two sources to the bundler, and a graph that is a cycle would instead
 * be an infinite regress cut by the depth bound and reported as a depth error naming nothing a
 * reader can act on. Every id goes through here, whether it came from an argument or an importer.
 *
 * <p>And then it is made readable again. A canonical path is absolute, which would print
 * {@code /Users/…/project/src/app.css:12:3} where the same file without {@code --bundle} prints
 * {@code src/app.css:12:3}, the same tool disagreeing with itself about what a file is called. A
 * path under the working directory is relativized against it, which is deterministic, so two
 * spellings of one file still collapse to one id. Anything outside keeps its absolute path, since
 * there is nothing shorter that is still unambiguous.
 */
final class SourceIds {

    /**
     * What standard input is called, having no path to canonicalize.
     */
    static final String STDIN = "<stdin>";

    /**
     * @param input a command-line input, or {@code "-"} for standard input
     * @return its id
     */
    static String of(String input) {
        if (input.equals("-")) {
            return STDIN;
        }

        try {
            Path path = Path.of(input);
            return of(path.toRealPath());
        }
        catch (IOException | InvalidPathException e) {
            // Unreadable or not a path: keep what the user wrote, so the I/O error that is
            // about to be reported names the file they named. Cli reads every input before
            // bundling, so this branch describes something already on its way to exit 3.
            return input;
        }
    }

    /**
     * @param real a path already canonicalized with {@code toRealPath}
     * @return its id
     */
    static String of(Path real) {
        Path here = Path.of("").toAbsolutePath();
        return real.startsWith(here) ? here.relativize(real).toString() : real.toString();
    }

    private SourceIds() {
        // utility class
    }
}
