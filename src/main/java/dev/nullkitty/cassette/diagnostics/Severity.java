package dev.nullkitty.cassette.diagnostics;

/**
 * How much a {@link Diagnostic} matters.
 *
 * <p>There is no {@code FATAL}. CSS Syntax defines recovery for every malformed construct it
 * admits, so there is no input this parser gives up on; the worst case is an
 * {@link #ERROR} and a rule that was dropped.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#error-handling">CSS Syntax Level 3 §2.2 Error
 *      Handling</a>
 */
public enum Severity {

    /**
     * A construct was malformed and something was discarded: a rule with an invalid
     * selector, a declaration with no colon, a block left open at end of input.
     *
     * <p>The spec calls these parse errors. They are expected, not exceptional, every
     * browser recovers from them the same way, which is why the AST is still usable.
     */
    ERROR,

    /**
     * The input parsed, but something about it is worth saying: a construct that is valid
     * syntax and unlikely to be what the author meant.
     */
    WARNING;

    /**
     * Whether this severity means something was discarded.
     *
     * @return whether this is an {@link #ERROR}
     */
    public boolean isError() {
        return this == ERROR;
    }
}
