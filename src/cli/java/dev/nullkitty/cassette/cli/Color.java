package dev.nullkitty.cassette.cli;

/**
 * Whether to colour severity labels.
 */
enum Color {

    /**
     * Colour only when standing at a terminal.
     */
    AUTO,

    ALWAYS,

    NEVER;

    /**
     * Resolves {@link #AUTO} against the environment.
     *
     * <p>{@code System.console()} is null when either stream is redirected, which is the case
     * that matters: escape sequences in a file or a CI log are noise at best and corrupt a
     * scraped {@code file:line:col} at worst. It is also null under the test harness, so
     * {@code AUTO} is off there without the tests having to say so.
     *
     * @return whether to emit escape sequences
     */
    boolean enabled() {
        return switch (this) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> System.console() != null;
        };
    }

    static Color parse(String value) {
        return switch (value) {
            case "auto" -> AUTO;
            case "always" -> ALWAYS;
            case "never" -> NEVER;
            default -> null;
        };
    }
}
