package dev.nullkitty.cassette.cli;

/**
 * What a command line asks for.
 *
 * <p>Three outcomes, because {@code --help} and {@code --version} are not degenerate runs: they
 * take no input, produce no diagnostics and always exit 0, and folding them into {@link Options}
 * as two more booleans would put a branch in front of every field that has nothing to do with
 * them.
 */
sealed interface Invocation {

    /**
     * Do the work.
     */
    record Run(Options options) implements Invocation {
    }

    /**
     * Print usage and exit 0.
     *
     * @param verb the verb to describe, or {@code null} for the overview
     */
    record ShowHelp(Verb verb) implements Invocation {
    }

    /**
     * Print the version and exit 0.
     */
    record ShowVersion() implements Invocation {
    }
}
