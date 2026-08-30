package dev.nullkitty.cassette.ast;

import java.util.List;

/**
 * Compound selectors joined by combinators: {@code .card > .title em}.
 *
 * <p>Stored as a flat list of steps rather than a left-leaning tree. Flattening walks these
 * left to right looking for the leading {@code &}, and a list makes "is the nesting selector
 * the very first thing in the first compound" a direct question instead of a recursive one.
 *
 * <p>Every step after the first carries the combinator that precedes it, and the first carries
 * {@link Combinator#NONE}. The exception is {@code :has()}, which takes a <em>relative</em>
 * selector list, so a selector may open with a combinator relating it to the scoping element, as in
 * {@code :has(> img)}. The first step carries that instead.
 *
 * @param steps      the compound selectors and the combinators joining them, never empty
 * @param packedSpan the packed region of source this selector was parsed from
 */
public record ComplexSelector(List<CombinatorStep> steps, //
                              long packedSpan)
    implements
        Selector {

    /**
     * Copies {@code steps} and rejects an empty selector, which the grammar has no
     * production for.
     *
     * @throws IllegalArgumentException if {@code steps} is empty
     * @throws NullPointerException if any argument or element is {@code null}
     */
    public ComplexSelector {
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a complex selector needs at least one compound selector");
        }
    }

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public ComplexSelector(List<CombinatorStep> steps, //
                           SourceSpan span) {
        this(steps, span.packed());
    }

    /**
     * Wraps a single compound selector as a one-step complex selector.
     *
     * @param compound the only compound selector
     * @return a complex selector holding just it
     */
    public static ComplexSelector of(CompoundSelector compound) {
        return new ComplexSelector(List.of(new CombinatorStep(Combinator.NONE, compound, compound.span())),
                                   compound.span());
    }

    /**
     * The leftmost compound selector, the one a leading {@code &} would live in.
     *
     * @return the first step's compound selector
     */
    public CompoundSelector first() {
        return this.steps.get(0).compound();
    }

    /**
     * The rightmost compound selector: the subject of the selector.
     *
     * @return the last step's compound selector
     */
    public CompoundSelector subject() {
        return this.steps.get(this.steps.size() - 1).compound();
    }

    /**
     * Whether a {@link NestingSelector} appears anywhere in this selector, at any depth
     * including inside a functional pseudo-class's arguments.
     *
     * @return whether flattening has to rewrite this selector
     */
    public boolean containsNestingSelector() {
        // An indexed loop rather than a stream, because this is asked once per selector on the
        // nesting-expansion path and the three implementations of it are mutually recursive: a
        // stream here allocates a pipeline per selector, per compound inside it, and again per
        // functional pseudo-class's argument list.
        for (int at = 0; at < this.steps.size(); at++) {
            if (this.steps.get(at).compound().containsNestingSelector()) {
                return true;
            }
        }

        return false;
    }
}
