package dev.nullkitty.cassette.serializer;

/**
 * How {@code &} is expanded when {@link NestingMode#FLATTEN} rewrites a nested rule.
 *
 * <p>Only matters when the parent rule's prelude holds more than one alternative, or when
 * {@code &} sits somewhere a parent selector cannot be spliced into.
 */
public enum NestingExpansion {

    /**
     * Wrap the parent selector list in {@code :is()}: one rule out for one rule in,
     * specificity preserved.
     *
     * <pre>
     * .card, .panel { &amp; .title { } }   becomes   :is(.card, .panel) .title { }
     * </pre>
     *
     * <p>The spec-correct expansion, and the default. {@code :is()} has shipped in every
     * evergreen engine since early 2021.
     */
    IS_WRAP,

    /**
     * Write one alternative per parent selector instead, avoiding {@code :is()} for engines
     * that predate it.
     *
     * <pre>
     * .card, .panel { &amp; .title { } }   becomes   .card .title, .panel .title { }
     * </pre>
     *
     * <p>Specificity is not preserved in general, {@code :is()} takes its most specific
     * argument, while each duplicate carries only its own weight, and the output grows with
     * the product of the nesting depth's selector counts. Note also that a {@code &} in a
     * position no single parent selector can be spliced into still falls back to
     * {@code :is()}, because there is nothing else correct to write.
     */
    DUPLICATE
}
