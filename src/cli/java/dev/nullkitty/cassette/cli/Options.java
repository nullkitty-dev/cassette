package dev.nullkitty.cassette.cli;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

import dev.nullkitty.cassette.serializer.SerializerOptions;

/**
 * One invocation, parsed and validated.
 *
 * <p>Everything the library needs is already in library types by the time this exists: the
 * serializer axes as a {@link SerializerOptions}, the encoding as a {@link Charset}. The commands
 * below therefore never re-interpret a flag. Whether the right options reached the library is the
 * thing worth testing about a CLI, and this record is where that is asserted.
 *
 * @param verb            what to do
 * @param inputs          paths to read, in the order given; {@code "-"} means standard input
 * @param output          {@code -o}, or {@code null}
 * @param outDir          {@code --out-dir}, or {@code null}
 * @param inPlace         whether to rewrite each input where it sits
 * @param serializer      the four serializer axes, formatting included from the verb
 * @param optimizations   {@code -O}, empty when none were asked for, and in
 *                        {@link Transform}'s order rather than the command line's
 * @param charset         {@code --charset}, or {@code null} for detection alone
 * @param quiet           whether to print errors only
 * @param strict          whether warnings affect the exit code
 * @param color           whether to colour severity labels
 * @param format          whether to draw a snippet under each diagnostic
 * @param maxDiagnostics  how many to print before summarizing the rest
 * @param bundle          whether the inputs are one stylesheet in cascade order
 * @param importRoots     {@code --import-root}, in the order given; empty means each input's
 *                        own directory, which {@link Cli} resolves because it is the one that
 *                        knows the inputs
 * @param noImports       whether to leave every {@code @import} in the output
 * @param banners         whether to mark each source's contents with a comment naming it
 * @param maxImportDepth  how deep {@code @import} may nest
 * @param sourceMap       {@code --source-map}; {@link SourceMapMode#NONE} unless asked for
 * @param sourceMapUrl    {@code --source-map-url}, or {@code null} to name the map file itself
 * @param sourceMapContent whether the map carries {@code sourcesContent}. It is the dominant cost
 *                        of a map, and {@code --no-source-map-content} is what removes it
 */
record Options(Verb verb,
               List<String> inputs,
               Path output,
               Path outDir,
               boolean inPlace,
               SerializerOptions serializer,
               List<Transform> optimizations,
               Charset charset,
               boolean quiet,
               boolean strict,
               Color color,
               DiagnosticFormat format,
               int maxDiagnostics,
               boolean bundle,
               List<Path> importRoots,
               boolean noImports,
               boolean banners,
               int maxImportDepth,
               SourceMapMode sourceMap,
               String sourceMapUrl,
               boolean sourceMapContent) {

    /**
     * Default for {@code --max-diagnostics}.
     */
    static final int DEFAULT_MAX_DIAGNOSTICS = 100;

    Options {
        inputs = List.copyOf(inputs);
        optimizations = List.copyOf(optimizations);
        importRoots = List.copyOf(importRoots);
    }

    /**
     * Whether output goes to standard output.
     *
     * <p>True for a single input with no destination, and for any number of them under
     * {@code --bundle}, which produces one stylesheet however many it read.
     */
    boolean writesToStdout() {
        return this.verb.writesOutput() && this.output == null && this.outDir == null && !this.inPlace;
    }

    /**
     * Whether this run generates a map.
     *
     * <p>{@code check --source-map} is accepted and does nothing, exactly as {@code check -O}
     * is, so the verb is part of the question rather than something the caller has to have
     * checked first.
     *
     * @return whether anything should build one
     */
    boolean generatesSourceMap() {
        return this.verb.writesOutput() && this.sourceMap.generates();
    }
}
