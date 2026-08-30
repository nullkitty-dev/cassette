package dev.nullkitty.cassette.cli;

/**
 * The invocation was wrong, as opposed to the CSS being wrong.
 *
 * <p>Separated from every other failure because it is acted on by a different person: exit 2
 * means fix your command line, exit 1 means fix your stylesheet, and a script that cannot tell
 * them apart will retry the wrong one.
 */
final class UsageException extends Exception {

    private static final long serialVersionUID = 1L;

    UsageException(String message) {
        super(message);
    }
}
