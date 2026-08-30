package dev.nullkitty.cassette.serializer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.diagnostics.LineIndex;
import dev.nullkitty.cassette.diagnostics.SourceResolver;
import dev.nullkitty.cassette.sourcemap.SourceMap;

/**
 * Output offsets into a source map, in one pass after the last character is written.
 *
 * <p>The writer records an offset and a packed span and nothing else, no line, no column and no
 * file, because everything else can be worked out once the output is final and none of it can be
 * rolled back. This pass works it out.
 *
 * @see <a href="https://tc39.es/ecma426/#sec-mappings">Source Map (ECMA-426) §9.2 Mappings structure</a>
 */
final class MapPass {

    /**
     * Builds the map for one serialization.
     *
     * @param css      what the writer produced
     * @param mappings what it recorded while producing it, in non-decreasing offset order
     * @param sources  where the spans came from
     * @return the map, with {@code file} and {@code sourceRoot} unset
     */
    static SourceMap generate(String css, Mappings mappings, SourceResolver sources) {
        SourceMap.Builder map = SourceMap.builder(mappings.size());

        // Keyed on the source id, and that is an invariant rather than a convenience. Mappings
        // arrive in output order, which for a bundle is cascade order, and a bundle's segments
        // are laid out in decode order, so consecutive mappings hop between files, and a
        // one-entry "whichever source was last" cache would miss on nearly all of them.
        Map<String, LineIndex> lineIndexes = new HashMap<>();

        int generatedLine = 0;
        int lineStart = 0;
        int walked = 0;

        for (int index = 0; index < mappings.size(); index++) {
            int offset = mappings.outputOffset(index);

            // One merge walk over the output rather than a search per mapping, which is what
            // recording offsets in increasing order buys. Minified output never enters this
            // loop body and degenerates to column == offset.
            while (walked < offset) {
                if (css.charAt(walked) == '\n') {
                    generatedLine++;
                    lineStart = walked + 1;
                }

                walked++;
            }

            Optional<SourceResolver.Location> located =
                sources.tryLocate(SourceSpan.unpack(mappings.packedSpan(index)));

            if (located.isEmpty()) {
                // A span covering more than one source, which is a wrapper the bundler
                // synthesized around an imported sheet that imported others. Dropped without a
                // diagnostic: a map is best effort, and warning here would fire once per
                // '@import' on every bundle.
                continue;
            }

            SourceResolver.Location at = located.get();

            // A get and a null test rather than computeIfAbsent, which is not a style choice.
            // The lambda captures the location, so it is allocated at the call site on every
            // mapping, hit or miss, and capturing the location there is what stops the location
            // itself from being scalarized. Between them they are the largest term in building a
            // map, and this is a walk over one thread's own map with no reason to hand the
            // insertion to it.
            LineIndex lines = lineIndexes.get(at.sourceId());
            if (lines == null) {
                lines = new LineIndex(at.sourceText());
                lineIndexes.put(at.sourceId(), lines);
            }

            map.mapping(generatedLine,
                        offset - lineStart,
                        map.source(at.sourceId(), at.sourceText()),
                        lines.lineOf(at.offset()),
                        lines.columnOf(at.offset()));
        }

        return map.build();
    }

    private MapPass() {
        // utility class
    }
}
