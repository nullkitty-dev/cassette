package dev.nullkitty.cassette.diagnostics;

import java.util.function.Consumer;

import dev.nullkitty.cassette.ast.SourceSpan;

/**
 * Something a stage of the pipeline noticed, reported rather than thrown.
 *
 * <p>Parsing never throws for recoverable input, and CSS Syntax makes almost everything
 * recoverable, so forcing callers to wrap a parse in a {@code try} would be exception
 * handling for the normal case. A returned list is also what stays testable: a golden
 * fixture can assert on diagnostics the same way it asserts on the tree.
 *
 * <p>The parser produces most of these, but not all of them: the serializer reports through
 * an optional sink when it has to drop a value it cannot spell, and it does not parse
 * anything. That is why this type sits in a package of its own rather than in {@code parser}
 * by the time four things produce a diagnostic and one of them is a parser, naming it after
 * the parser describes the majority rather than the type.
 *
 * @param severity how much this matters
 * @param message  a human-readable description, lowercase and without a trailing period
 * @param span     the region of source the problem was found at
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#error-handling">CSS Syntax Level 3 §2.2 Error
 *      Handling</a>
 */
public record Diagnostic(Severity severity, String message, SourceSpan span) {

    /**
     * A sink that throws its diagnostics away.
     *
     * <p>Every stage that can report takes a {@code Consumer<Diagnostic>}, and every
     * overload that does not expose one needs something to pass. Nothing in that position
     * is worth allocating a list for, and an inline {@code diagnostic -> { }} written at
     * each of those sites says less about the intent than a name does.
     */
    public static final Consumer<Diagnostic> DISCARD = diagnostic -> {
    };

    /**
     * Builds an {@link Severity#ERROR}.
     *
     * @param message the description
     * @param span    where the problem is
     * @return the diagnostic
     */
    public static Diagnostic error(String message, //
                                   SourceSpan span) {
        return new Diagnostic(Severity.ERROR, message, span);
    }

    /**
     * Builds a {@link Severity#WARNING}.
     *
     * @param message the description
     * @param span    where the problem is
     * @return the diagnostic
     */
    public static Diagnostic warning(String message, //
                                     SourceSpan span) {
        return new Diagnostic(Severity.WARNING, message, span);
    }

    @Override
    public String toString() {
        return this.severity + " " + this.span + " " + this.message;
    }
}
