package dev.nullkitty.cassette.bundle;

import java.util.List;

import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.diagnostics.Severity;

/**
 * What a bundle produced: one tree, everything noticed while assembling it, and the map back to
 * the sources it came from.
 *
 * <p>The tree is an ordinary {@link Stylesheet}, so {@code Flattener}, {@code Optimizer} and
 * {@code CssSerializer} take it unchanged. What is not ordinary is that its spans are offsets
 * in a coordinate space spanning every source, which makes {@code SourceSpan.text} the wrong
 * tool for reading any of them, {@link #sourceIndex()} is how a span becomes text or a file
 * name.
 *
 * @param ast         the assembled stylesheet
 * @param diagnostics everything noticed, from every source's parse and from assembling them, in
 *                    the order they were found, which for a bundle is source-by-source in
 *                    layout order
 * @param sourceIndex the segment table, for resolving any span in {@code ast}
 */
public record BundleResult(Stylesheet ast, //
                           List<Diagnostic> diagnostics,
                           SourceIndex sourceIndex) {

    /**
     * Copies {@code diagnostics} so the record is genuinely immutable.
     *
     * @throws NullPointerException if any argument or diagnostic is {@code null}
     */
    public BundleResult {
        diagnostics = List.copyOf(diagnostics);
    }

    /**
     * Whether anything was discarded.
     *
     * @return whether any diagnostic is an {@link Severity#ERROR}
     */
    public boolean hasErrors() {
        return this.diagnostics.stream().anyMatch(d -> d.severity().isError());
    }

    /**
     * The diagnostics of one severity.
     *
     * @param severity the severity to keep
     * @return the matching diagnostics, in the order they were found
     */
    public List<Diagnostic> diagnostics(Severity severity) {
        return this.diagnostics.stream().filter(d -> d.severity() == severity).toList();
    }
}
