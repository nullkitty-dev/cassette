package dev.nullkitty.cassette.ast;

/**
 * One compound selector within a {@link ComplexSelector}, together with the combinator that
 * precedes it.
 *
 * <p>Not a {@link Node}: a step is a pairing internal to a complex selector, not something
 * that exists on its own in the source. It carries a span anyway, covering the combinator
 * and the compound selector together, so diagnostics can point at a whole step.
 *
 * @param combinator how this step relates to the one before it; {@link Combinator#NONE} for
 *                   the first step in a selector
 * @param compound   the compound selector at this step
 * @param packedSpan the packed region of source covering the combinator and the compound selector
 */
public record CombinatorStep(Combinator combinator, //
                             CompoundSelector compound,
                             long packedSpan) {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public CombinatorStep(Combinator combinator, //
                          CompoundSelector compound,
                          SourceSpan span) {
        this(combinator, compound, span.packed());
    }

    /**
     * Where this step came from.
     *
     * @return the region of source it was parsed from
     */
    public SourceSpan span() {
        return SourceSpan.unpack(this.packedSpan);
    }
}
