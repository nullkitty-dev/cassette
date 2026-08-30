package dev.nullkitty.cassette.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.nullkitty.cassette.bundle.Importer;
import dev.nullkitty.cassette.bundle.Origin;
import dev.nullkitty.cassette.bundle.Source;

/**
 * Resolves an {@code @import} against the filesystem, inside a fence.
 *
 * <p>The library owns decoding, parsing, recursion and cycle detection and never touches a
 * filesystem, so this is the whole of what the CLI adds.
 *
 * <p>Two policies live here because the library cannot hold them. Only the caller knows where the
 * boundary of a project is, and whether fetching is acceptable at all.
 *
 * <ul>
 *   <li><b>Resolution happens only within a declared root.</b> Every {@code --import-root} is a
 *       root, and with none declared each input file's own directory is one. A specifier that
 *       escapes every root resolves to nothing.
 *   <li><b>Nothing is ever fetched over a network.</b> Guaranteed by there being no code here
 *       that opens a connection, and enforced twice over by the root fence. A specifier with a
 *       scheme names no file inside a root, and a protocol-relative {@code //} resolves to an
 *       absolute path outside every one of them.
 * </ul>
 *
 * <p>{@link #hasScheme} is an early exit rather than the guarantee, despite reading like one.
 * Removing it changes no behaviour a test can observe. It stays because it states the policy in
 * one place, and because it keeps a file whose name contains a colon from being reachable by a
 * specifier that reads as a URL.
 *
 * <p>Declining is not an error. An empty result leaves the {@code @import} in the output with a
 * warning, so a stylesheet importing a web font still bundles and the browser fetches the font.
 * There is no separate "allow" list to maintain and no flag that turns the fence off.
 *
 * <p>A relative specifier resolves against the importing sheet's own directory, which is what CSS
 * says and what every author expects, rather than against a root or the working directory. The
 * roots are a fence around the answer, not the base for computing it.
 */
final class FileImporter implements Importer {

    private final List<Path> roots;

    /**
     * @param roots where imports may resolve; a specifier landing outside all of them is
     *              declined. An empty list declines everything.
     */
    FileImporter(List<Path> roots) {
        List<Path> real = new ArrayList<>(roots.size());

        for (Path root : roots) {
            // A root that does not exist cannot contain anything, so it is dropped here rather
            // than re-examined per specifier. Resolved once: the containment test below runs
            // per @import, and toRealPath is a syscall.
            Path resolved = realPath(root);
            if (resolved != null && Files.isDirectory(resolved)) {
                real.add(resolved);
            }
        }

        this.roots = List.copyOf(real);
    }

    @Override
    public Optional<Source> resolve(String specifier, //
                                    Origin from) {
        if (this.roots.isEmpty() || hasScheme(specifier)) {
            return Optional.empty();
        }

        Path candidate = against(from.sourceId(), specifier);
        if (candidate == null || !Files.isRegularFile(candidate) || !Files.isReadable(candidate)) {
            return Optional.empty();
        }

        // The real path is both the fence test and the id. Comparing normalized paths would
        // pass a symlink pointing out of the root; comparing real ones cannot, and the same
        // canonical form is then what cycle detection compares, so `a.css` and `./a.css` and a
        // link to either are one source rather than an infinite regress bounded by the depth
        // limit. Canonicalizing is the importer's job, and this is where it is done.
        Path real = realPath(candidate);
        if (real == null || !within(real)) {
            return Optional.empty();
        }

        try {
            return Optional.of(new Source(SourceIds.of(real), Files.readAllBytes(real)));
        }
        catch (IOException e) {
            // resolve() cannot throw a checked exception, and this file was readable a moment
            // ago, so a failure here is a real I/O fault rather than a decline. Cli catches it
            // and exits 3; swallowing it would silently leave the @import in the output as
            // though the file had never existed.
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Whether a specifier names something other than a path.
     *
     * <p>Broader than "http": anything with a scheme is somebody else's to fetch,
     * and {@code //fonts.example/css} is protocol-relative and equally not a file. A Windows
     * drive letter would false-positive on a single-character scheme, which is why the scheme
     * has to be at least two characters.
     */
    private static boolean hasScheme(String specifier) {
        if (specifier.startsWith("//")) {
            return true;
        }

        int colon = specifier.indexOf(':');
        if (colon < 2) {
            return false;
        }

        for (int i = 0; i < colon; i++) {
            char c = specifier.charAt(i);
            boolean legal = (c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9')
                            || c == '+'
                            || c == '-'
                            || c == '.';

            if (!legal) {
                return false;
            }
        }

        return true;
    }

    /**
     * Resolves a specifier against the directory of the sheet that wrote it.
     *
     * <p>Standard input has no directory, so it uses the working directory, stated here
     * rather than left to fall out of {@code Path.of("<stdin>")}, which is a legal file name on
     * POSIX and not on Windows, and would otherwise make this resolve on one and decline on the
     * other. It still has to clear a root to be imported, and stdin contributes none, so this
     * is reachable only when {@code --import-root} was declared.
     */
    private static Path against(String sourceId, String specifier) {
        try {
            Path directory = sourceId.equals(SourceIds.STDIN) ? Path.of("").toAbsolutePath()
                                                              : Path.of(sourceId).toAbsolutePath().getParent();
            return directory == null ? null : directory.resolve(specifier).normalize();
        }
        catch (InvalidPathException e) {
            // The specifier is not a path on this platform, a URL-ish one that got past
            // hasScheme, or a name carrying a character the filesystem refuses.
            return null;
        }
    }

    private boolean within(Path real) {
        for (Path root : this.roots) {
            if (real.startsWith(root)) {
                return true;
            }
        }

        return false;
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        }
        catch (IOException e) {
            return null;
        }
    }
}
