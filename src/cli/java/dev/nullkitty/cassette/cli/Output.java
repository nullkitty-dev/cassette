package dev.nullkitty.cassette.cli;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writing a stylesheet to a file, without the chance of leaving half of one.
 *
 * <p>Every write goes to a temporary file in the destination's own directory and is then renamed
 * over the target. {@code --in-place} is what makes this necessary, since a failure part-way
 * through writing over the input destroys the only copy. The same failure truncates an existing
 * {@code -o} target just as thoroughly, so there is one rule and no exceptions to remember.
 *
 * <p>The temporary file is a sibling rather than in the system temp directory, because a rename
 * across filesystems is a copy, which is neither atomic nor able to fail cleanly. Atomicity is the
 * entire property being bought.
 *
 * <p>Output is UTF-8, not the encoding the input was read in. Matching the input would need the
 * detected encoding back out of the library, which the surface does not offer, and it would write a
 * legacy encoding back out by default. What it costs is a stylesheet that declares
 * {@code @charset} for something else, which {@link Cli} warns about rather than silently
 * producing.
 */
final class Output {

    /**
     * @param css    the stylesheet to write
     * @param target where it goes; its directory is created if missing
     * @throws IOException if the write or the rename fails
     */
    static void write(String css, Path target) throws IOException {
        Path temporary = stage(target);
        try {
            Files.writeString(temporary, css, StandardCharsets.UTF_8);
            move(temporary, target);
        }
        finally {
            // A no-op after a successful rename, and the cleanup that keeps a failed write
            // from leaving litter next to the file it did not replace.
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Something that can write itself, for content there is no reason to hold as a {@code String}.
     *
     * <p>Exists for the source map. Its JSON is mostly {@code sourcesContent}, so on a 3.6 MB
     * stylesheet {@code toJson} builds about 4.4 MB purely so that this class can copy it into a
     * file, and {@code SourceMap.writeJson} takes an {@code Appendable} precisely so nobody has
     * to.
     */
    interface Content {

        /**
         * @param out where to write
         * @throws IOException if {@code out} does
         */
        void writeTo(Appendable out) throws IOException;
    }

    /**
     * As {@link #write(String, Path)}, for content that writes itself.
     *
     * <p>Same temporary file and same rename, because the reason for those has nothing to do with
     * how the content was produced: a failure part-way through must not leave half a file where a
     * whole one was.
     *
     * @param content what to write
     * @param target  where it goes; its directory is created if missing
     * @throws IOException if the write or the rename fails
     */
    static void write(Content content, Path target) throws IOException {
        Path temporary = stage(target);
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                content.writeTo(writer);
            }

            move(temporary, target);
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * The destination's directory, created, with an empty sibling to write into.
     */
    private static Path stage(Path target) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        return Files.createTempFile(directory, ".cassette-", ".tmp");
    }

    private static void move(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e) {
            // Some filesystems cannot promise it. Replacing is still better than writing the
            // target directly, since the content was fully written before anything was moved.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Output() {
        // utility class
    }
}
