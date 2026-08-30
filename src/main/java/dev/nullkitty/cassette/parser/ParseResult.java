package dev.nullkitty.cassette.parser;

import java.util.List;

import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.diagnostics.Severity;

/**
 * What a parse produced: a tree, and everything the parser wanted to say about the input.
 *
 * <p>The tree is always present. A stylesheet of nothing but garbage parses to an empty
 * {@link Stylesheet} and a list of errors rather than to a failure, which is what spec-faithful
 * recovery means. There is no {@code isSuccess()}: whether a stylesheet with three dropped rules
 * counts as one is the caller's judgement, and {@link #diagnostics()} is what they judge it
 * with.
 *
 * @param ast         the parsed stylesheet
 * @param diagnostics everything noticed while parsing, in source order
 */
public record ParseResult(Stylesheet ast, //
                          List<Diagnostic> diagnostics) {

    /**
     * Copies {@code diagnostics} so the record is genuinely immutable.
     *
     * @throws NullPointerException if either argument or any diagnostic is {@code null}
     */
    public ParseResult {
        diagnostics = List.copyOf(diagnostics);
    }

    /**
     * Whether anything was discarded during the parse.
     *
     * @return whether any diagnostic is an {@link Severity#ERROR}
     */
    public boolean hasErrors() {
        return this.diagnostics.stream() //
                               .anyMatch(d -> d.severity().isError());
    }

    /**
     * The diagnostics of one severity.
     *
     * @param severity the severity to keep
     * @return the matching diagnostics, in source order
     */
    public List<Diagnostic> diagnostics(Severity severity) {
        return this.diagnostics.stream() //
                               .filter(d -> d.severity() == severity) //
                               .toList();
    }
}
