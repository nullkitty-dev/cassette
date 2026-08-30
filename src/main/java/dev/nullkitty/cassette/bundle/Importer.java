package dev.nullkitty.cassette.bundle;

import java.util.Optional;

/**
 * Turns an {@code @import} specifier into bytes, or declines.
 *
 * <pre>{@code
 * Importer files = (specifier, from) -> {
 *     Path path = root.resolve(specifier).normalize();
 *     return path.startsWith(root) && Files.isReadable(path)
 *             ? Optional.of(new Source(path.toString(), Files.readAllBytes(path)))
 *             : Optional.empty();
 * };
 * }</pre>
 *
 * <p>cassette never touches a filesystem, a classpath or a network. It owns decoding, charset and
 * BOM detection, parsing, recursion and cycle detection. The importer owns everything cassette
 * cannot know: what a specifier means, whether it is relative to the importing file, and whether it
 * should be fetched at all. A policy like "never leave this directory" belongs here, since only
 * the caller knows where the boundary is.
 *
 * <p>Declining is a supported answer rather than a failure. An empty result leaves the
 * {@code @import} rule in the output verbatim, so whatever consumes the CSS resolves it at
 * runtime: inline the local partials, leave the web font URL alone.
 *
 * <p>Canonicalizing is the importer's job. The {@code id} on the returned source is compared for
 * cycle detection and printed in diagnostics and banners, and is otherwise never interpreted. Two
 * ids differing by a {@code ../} are therefore two different sources, and a cycle between them
 * becomes an infinite regress that only the depth limit stops.
 */
@FunctionalInterface
public interface Importer {

    /**
     * Resolves one specifier.
     *
     * @param specifier the text of the {@code @import}'s url or string, decoded, escapes are
     *                  already resolved, so this is the name the author meant
     * @param from      where the {@code @import} sits: which source, and where in it
     * @return the imported stylesheet, or empty to leave the rule in the output
     */
    Optional<Source> resolve(String specifier, //
                             Origin from);
}
