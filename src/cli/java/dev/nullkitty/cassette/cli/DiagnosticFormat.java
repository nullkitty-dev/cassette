package dev.nullkitty.cassette.cli;

/**
 * How much of the source to draw around a diagnostic.
 *
 * <p>Modelled on {@link Color}, and for the same reason rather than for symmetry: a redirected
 * stream is being read by a machine, and the {@code file:line:col:} shape is what every editor
 * and CI log scraper already parses. A gutter and a caret are for a person looking at a
 * terminal. So the environment decides by default, exactly as it decides about escape sequences.
 */
enum DiagnosticFormat {

    /**
     * A snippet only when standing at a terminal.
     */
    AUTO,

    /**
     * The line, a gutter and a caret under the span.
     */
    RICH,

    /**
     * One line per diagnostic, plus a {@code note:} line carrying the source line.
     */
    SHORT;

    /**
     * Resolves {@link #AUTO} against the environment.
     *
     * <p>{@code System.console()} is null when either stream is redirected, and under the test
     * harness, so {@code AUTO} is short there without a test having to say so, and the
     * renderer's existing assertions keep describing the default.
     *
     * @return whether to draw a snippet
     */
    boolean rich() {
        return switch (this) {
            case RICH -> true;
            case SHORT -> false;
            case AUTO -> System.console() != null;
        };
    }

    static DiagnosticFormat parse(String value) {
        return switch (value) {
            case "auto" -> AUTO;
            case "rich" -> RICH;
            case "short" -> SHORT;
            default -> null;
        };
    }
}
