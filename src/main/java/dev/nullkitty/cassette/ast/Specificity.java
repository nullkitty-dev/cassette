package dev.nullkitty.cassette.ast;

import java.util.List;

/**
 * A selector's weight in the cascade, as the (A, B, C) triple of Selectors Level 4 §15.
 *
 * <p>Public API because the library already needs it internally: {@code :is()}-wrapping during
 * flattening is specificity-preserving precisely because it can compute this. Any tool doing CSS
 * analysis or linting wants it too, and once selectors are structural it is a small pure function.
 *
 * <p>The counts are not digits of a base-10 number. Eleven classes beat one id in no ordering
 * anyone has ever implemented, so comparison is lexicographic on the triple and the counts are
 * never carried or clamped.
 *
 * @param idCount    A: id selectors
 * @param classCount B: class selectors, attribute selectors and pseudo-classes
 * @param typeCount  C: type selectors and pseudo-elements
 * @see <a href="https://www.w3.org/TR/selectors-4/#specificity-rules">Selectors Level 4 §15</a>
 */
public record Specificity(int idCount, //
                          int classCount,
                          int typeCount)
    implements
        Comparable<Specificity> {

    /**
     * The specificity of a selector matching nothing in particular: {@code *}.
     */
    public static final Specificity ZERO = new Specificity(0, 0, 0);

    /**
     * One id selector.
     */
    private static final Specificity ID = new Specificity(1, 0, 0);

    /**
     * One class, attribute or pseudo-class selector.
     */
    private static final Specificity CLASS = new Specificity(0, 1, 0);

    /**
     * One type selector or pseudo-element.
     */
    private static final Specificity TYPE = new Specificity(0, 0, 1);

    /**
     * Rejects negative counts, which only arise from arithmetic bugs.
     *
     * @throws IllegalArgumentException if any count is negative
     */
    public Specificity {
        if (idCount < 0 || classCount < 0 || typeCount < 0) {
            throw new IllegalArgumentException("counts must not be negative: "
                                               + idCount
                                               + ", "
                                               + classCount
                                               + ", "
                                               + typeCount);
        }
    }

    /**
     * The specificity of any selector.
     *
     * <p>For a {@link SelectorList} this is the specificity of its most specific
     * alternative, which is what {@code :is()} contributes, not what the list as a rule
     * prelude contributes, since there each alternative is weighed on its own.
     *
     * <p>A {@link NestingSelector} counts as zero. Its real weight is that of the enclosing
     * rule's selector list, which a node with no parent pointer cannot see; compute
     * specificity after flattening has substituted the parent in.
     *
     * @param selector the selector to weigh
     * @return its specificity
     */
    public static Specificity of(Selector selector) {
        return switch (selector) {
            case SelectorList list -> maxOf(list.selectors());

            case ComplexSelector complex -> complex.steps().stream().map(step -> of(step.compound()))
                                                   .reduce(ZERO, Specificity::plus);

            case CompoundSelector compound -> compound.simples().stream().map(Specificity::of)
                                                      .reduce(ZERO, Specificity::plus);

            case IdSelector ignored -> ID;
            case ClassSelector ignored -> CLASS;
            case AttributeSelector ignored -> CLASS;
            case TypeSelector type -> type.isUniversal() ? ZERO : TYPE;
            case PseudoElementSelector ignored -> TYPE;
            case NestingSelector ignored -> ZERO;
            case PseudoClassSelector pseudo -> ofPseudoClass(pseudo);
        };
    }

    /**
     * §15's three special cases, and the ordinary one.
     *
     * <ul>
     *   <li>{@code :where()} contributes nothing, however specific its contents.</li>
     *   <li>{@code :is()}, {@code :not()} and {@code :has()} contribute their most specific
     *       argument and nothing of their own.</li>
     *   <li>{@code :nth-child(An+B of S)} contributes a pseudo-class <em>plus</em> its most
     *       specific argument.</li>
     * </ul>
     *
     * @see <a href="https://www.w3.org/TR/selectors-4/#specificity-rules">Selectors Level 4 §15</a>
     */
    private static Specificity ofPseudoClass(PseudoClassSelector pseudo) {
        if (pseudo.isZeroSpecificity()) {
            return ZERO;
        }

        if (pseudo.selectors() == null) {
            return CLASS;
        }

        Specificity inner = maxOf(pseudo.selectors().selectors());
        if (PseudoClassSelector.takesNthOfSelectorList(pseudo.name())) {
            return CLASS.plus(inner);
        }

        return inner;
    }

    private static Specificity maxOf(List<ComplexSelector> selectors) {
        Specificity max = ZERO;

        for (ComplexSelector selector : selectors) {
            Specificity candidate = of(selector);
            if (candidate.compareTo(max) > 0) {
                max = candidate;
            }
        }

        return max;
    }

    /**
     * Component-wise sum, for combining the parts of a compound or complex selector.
     *
     * @param other the specificity to add
     * @return the sum
     */
    public Specificity plus(Specificity other) {
        return new Specificity(this.idCount + other.idCount,
                               this.classCount + other.classCount,
                               this.typeCount + other.typeCount);
    }

    /**
     * Orders by id count, then class count, then type count.
     *
     * @param other the specificity to compare against
     * @return negative if this loses the cascade, positive if it wins, zero if they tie
     */
    @Override
    public int compareTo(Specificity other) {
        int byId = Integer.compare(this.idCount, other.idCount);
        if (byId != 0) {
            return byId;
        }

        int byClass = Integer.compare(this.classCount, other.classCount);
        return byClass != 0 ? byClass : Integer.compare(this.typeCount, other.typeCount);
    }

    @Override
    public String toString() {
        return "(" + this.idCount + "," + this.classCount + "," + this.typeCount + ")";
    }
}
