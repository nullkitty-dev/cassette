package dev.nullkitty.cassette.cli;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.List;

import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.diagnostics.Severity;
import dev.nullkitty.cassette.diagnostics.SourceResolver;

/**
 * Prints diagnostics, and remembers whether anything printed should fail the run.
 *
 * <p>Everything goes to standard error, including under {@code check}, whose whole output is
 * diagnostics. Standard output carries CSS and nothing else, so
 * {@code cassette minify a.css > a.min.css} cannot produce a file with an error message in the
 * middle of it. A {@code check} being read by a human is being read from a terminal, where the two
 * streams are interleaved anyway.
 */
final class DiagnosticReporter {

    private final PrintStream err;
    private final boolean     quiet;
    private final boolean     strict;
    private final boolean     color;
    private final boolean     rich;
    private final int         max;

    private int printed;
    private int suppressed;
    private int errors;
    private int warnings;

    DiagnosticReporter(PrintStream err, Options options) {
        this.err = err;
        this.quiet = options.quiet();
        this.strict = options.strict();
        this.color = options.color().enabled();
        this.rich = options.format().rich();
        this.max = options.maxDiagnostics();
    }

    /**
     * Reports everything found in one source.
     *
     * <p>Sorted by where they are, which is not the order the library produced them: recovery
     * reports an unclosed construct only once it has run out of input, so the parser's own
     * order can put the consequence before the cause. That is right for a list a program walks
     * and wrong for a list a person reads top to bottom against their file.
     *
     * @param diagnostics what was found
     * @param resolver    where the spans point
     */
    void report(List<Diagnostic> diagnostics, SourceResolver resolver) {
        DiagnosticRenderer renderer = new DiagnosticRenderer(resolver, this.rich);
        diagnostics.stream() //
                   .sorted(Comparator.comparingInt(diagnostic -> diagnostic.span().start())) //
                   .forEach(diagnostic -> report(diagnostic, renderer));
    }

    private void report(Diagnostic diagnostic, DiagnosticRenderer renderer) {
        // Counted before the quiet and max checks: what is on screen is a display decision and
        // what failed the build is not, so hiding a warning must not also excuse it.
        if (diagnostic.severity().isError()) {
            this.errors++;
        }
        else {
            this.warnings++;
        }

        if (this.quiet && !diagnostic.severity().isError()) {
            return;
        }

        if (this.printed >= this.max) {
            this.suppressed++;
            return;
        }

        // A blank line between snippets, and none in the short form. This is not the gutter
        // line the rich format deliberately drops: that one padded a single diagnostic, and
        // this separates two that would otherwise run together as one eight-line block.
        if (this.rich && this.printed > 0) {
            this.err.println();
        }

        this.printed++;

        this.err.println(colorize(renderer.render(diagnostic), diagnostic.severity()));
    }

    private String colorize(String rendered, Severity severity) {
        if (!this.color) {
            return rendered;
        }

        String label = severity.isError() ? "error:" : "warning:";
        String painted = (severity.isError() ? "[31m" : "[33m") + label + "[0m";

        // Only the first occurrence: the note line repeats the location, not the severity.
        return rendered.replaceFirst(java.util.regex.Pattern.quote(label), painted);
    }

    /**
     * Prints the count of anything {@code --max-diagnostics} held back.
     */
    void finish() {
        if (this.suppressed > 0) {
            this.err.println("... and " + this.suppressed + " more (--max-diagnostics " + this.max + ")");
        }
    }

    /**
     * @return whether the run should exit nonzero
     */
    boolean failed() {
        return this.errors > 0 || (this.strict && this.warnings > 0);
    }
}
