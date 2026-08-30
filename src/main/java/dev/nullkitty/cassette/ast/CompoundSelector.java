package dev.nullkitty.cassette.ast;

import java.util.List;

/**
 * Simple selectors written with nothing between them, all matching the same element:
 * {@code div.card:hover}.
 *
 * <p>Order is preserved but is not meaningful to matching, {@code .a.b} and {@code .b.a}
 * select the same elements. It is preserved because passthrough serialization should give
 * an author back what they wrote.
 *
 * <p>A type selector, if present, must come first; the parser enforces that, so a compound
 * selector in the tree always satisfies it.
 *
 * @param simples    the simple selectors, in source order, never empty
 * @param packedSpan the packed region of source this compound selector was parsed from
 */
public record CompoundSelector(List<SimpleSelector> simples, //
                               long packedSpan)
    implements
        Selector {

    /**
     * Copies {@code simples} and rejects an empty compound selector, which the grammar has no
     * production for.
     *
     * @throws IllegalArgumentException if {@code simples} is empty
     * @throws NullPointerException if any argument or element is {@code null}
     */
    public CompoundSelector {
        if (simples.isEmpty()) {
            throw new IllegalArgumentException("a compound selector needs at least one simple selector");
        }

        simples = List.copyOf(simples);
    }

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public CompoundSelector(List<SimpleSelector> simples, //
                            SourceSpan span) {
        this(simples, span.packed());
    }

    /**
     * Wraps a single simple selector as a one-element compound selector.
     *
     * @param simple the only simple selector
     * @return a compound selector holding just it
     */
    public static CompoundSelector of(SimpleSelector simple) {
        return new CompoundSelector(List.of(simple), simple.span());
    }

    /**
     * Whether a {@link NestingSelector} appears here or inside one of these selectors'
     * arguments.
     *
     * @return whether flattening has to rewrite this compound selector
     */
    public boolean containsNestingSelector() {
        // Indexed rather than a stream, for the reason ComplexSelector's copy gives: this is the
        // innermost of three mutually recursive answers to the same question.
        for (int at = 0; at < this.simples.size(); at++) {
            if (this.simples.get(at).containsNestingSelector()) {
                return true;
            }
        }

        return false;
    }
}
