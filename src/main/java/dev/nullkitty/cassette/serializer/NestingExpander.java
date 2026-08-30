package dev.nullkitty.cassette.serializer;

import java.util.ArrayList;
import java.util.List;

import dev.nullkitty.cassette.ast.Combinator;
import dev.nullkitty.cassette.ast.CombinatorStep;
import dev.nullkitty.cassette.ast.ComplexSelector;
import dev.nullkitty.cassette.ast.CompoundSelector;
import dev.nullkitty.cassette.ast.NestingSelector;
import dev.nullkitty.cassette.ast.PseudoClassSelector;
import dev.nullkitty.cassette.ast.PseudoElementSelector;
import dev.nullkitty.cassette.ast.SelectorList;
import dev.nullkitty.cassette.ast.SimpleSelector;
import dev.nullkitty.cassette.ast.TypeSelector;

/**
 * Absolutizes a nested rule's selector against its parent's.
 *
 * <p>Two cases, and which one applies is decided by whether {@code &} appears at all:
 *
 * <ul>
 *   <li><b>No {@code &}.</b> The selector is relative and the parent is prepended, so
 *       {@code .title} becomes {@code & .title} and {@code > .title} becomes
 *       {@code & > .title}. A leading combinator is kept and the descendant combinator is
 *       implied only in its absence.</li>
 *   <li><b>A {@code &} somewhere.</b> The selector is already absolute and nothing is
 *       prepended. Every {@code &} is replaced in place, which is what makes the
 *       {@code .theme-dark & { }} pattern mean {@code .theme-dark .parent} rather than
 *       {@code .parent .theme-dark .parent}.</li>
 * </ul>
 *
 * <p>Replacing a {@code &} in place has three outcomes, tried in this order, which is shortest
 * output first:
 *
 * <ol>
 *   <li>The {@code &} opens the selector and there is one parent alternative, so the parent's
 *       steps are spliced in. {@code .card .body} + {@code & > .title} is
 *       {@code .card .body > .title}.</li>
 *   <li>The parent is one alternative of one compound selector carrying no type selector or
 *       pseudo-element, so its simple selectors are inlined. {@code .card} + {@code .open &}
 *       is {@code .open.card}.</li>
 *   <li>Otherwise wrap in {@code :is(<parent>)}. This is the only correct answer when the
 *       parent has several alternatives, or is complex and the {@code &} is not leading.
 *       {@code .x > &} with a parent of {@code .a .b} is <em>not</em> {@code .x > .a .b},
 *       which relates {@code .x} to the wrong element entirely.</li>
 * </ol>
 *
 * @see <a href="https://www.w3.org/TR/css-nesting-1/#nest-selector">CSS Nesting Module Level 1 §4
 *      Nesting Selector: the &amp; selector</a>
 */
final class NestingExpander {

    /**
     * Rewrites {@code nested} so it stands on its own, without the enclosing rule.
     *
     * @param nested the nested rule's selector list, as written
     * @param parent the enclosing rule's selector list, already absolutized itself
     * @param mode   how a multi-alternative parent is expanded
     * @return the absolutized list
     */
    static SelectorList absolutize(SelectorList nested, SelectorList parent, NestingExpansion mode) {
        List<ComplexSelector> expanded = new ArrayList<>();

        for (ComplexSelector selector : nested.selectors()) {
            if (mode == NestingExpansion.DUPLICATE) {
                // One output selector per parent alternative, each substituted on its own,
                // so no :is() is needed unless a position forces it.
                for (ComplexSelector alternative : parent.selectors()) {
                    expanded.add(absolutizeOne(selector, SelectorList.of(alternative)));
                }
            }
            else {
                expanded.add(absolutizeOne(selector, parent));
            }
        }

        return new SelectorList(expanded, nested.span());
    }

    private static ComplexSelector absolutizeOne(ComplexSelector nested, SelectorList parent) {
        return nested.containsNestingSelector() ? substitute(nested, parent) : prepend(nested, parent);
    }

    // -----------------------------------------------------------------------
    // No '&': the selector is relative to the parent
    // -----------------------------------------------------------------------

    private static ComplexSelector prepend(ComplexSelector nested, SelectorList parent) {
        List<CombinatorStep> steps = new ArrayList<>(parentSteps(parent));

        CombinatorStep first = nested.steps().get(0);
        Combinator joining = first.combinator() == Combinator.NONE ? Combinator.DESCENDANT : first.combinator();

        steps.add(new CombinatorStep(joining, first.compound(), first.span()));
        steps.addAll(nested.steps().subList(1, nested.steps().size()));

        return new ComplexSelector(steps, nested.span());
    }

    /**
     * The parent as steps that can open a complex selector, {@code :is()}-wrapped if it must be.
     */
    private static List<CombinatorStep> parentSteps(SelectorList parent) {
        if (parent.isMultiple()) {
            CompoundSelector wrapped = CompoundSelector.of(wrap(parent));
            return List.of(new CombinatorStep(Combinator.NONE, wrapped, parent.span()));
        }

        List<CombinatorStep> steps = new ArrayList<>(parent.selectors().get(0).steps());
        CombinatorStep first = steps.get(0);

        if (first.combinator() != Combinator.NONE) {
            // A parent that was itself relative, inside :has(), say. Nothing precedes it
            // here, so its leading combinator has nothing left to relate to.
            steps.set(0, new CombinatorStep(Combinator.NONE, first.compound(), first.span()));
        }

        return steps;
    }

    // -----------------------------------------------------------------------
    // A '&' somewhere: the selector is absolute, every '&' is replaced in place
    // -----------------------------------------------------------------------

    private static ComplexSelector substitute(ComplexSelector nested, SelectorList parent) {
        List<CombinatorStep> steps = new ArrayList<>();
        List<CombinatorStep> nestedSteps = nested.steps();

        for (int index = 0; index < nestedSteps.size(); index++) {
            CombinatorStep step = nestedSteps.get(index);

            if (index == 0 && opensWithNesting(step) && !parent.isMultiple()) {
                spliceParent(steps, parent.selectors().get(0), step, parent);
            }
            else {
                steps.add(new CombinatorStep(step.combinator(), substitute(step.compound(), parent), step.span()));
            }
        }

        return new ComplexSelector(steps, nested.span());
    }

    private static boolean opensWithNesting(CombinatorStep step) {
        return step.combinator() == Combinator.NONE && step.compound().simples().get(0) instanceof NestingSelector;
    }

    /**
     * Case 1: the leading {@code &} becomes the parent's own steps, and whatever else that
     * compound selector held, the {@code .title} of {@code &.title}, joins the parent's
     * last compound selector.
     */
    private static void spliceParent(List<CombinatorStep> steps,
                                     ComplexSelector parentSelector,
                                     CombinatorStep step,
                                     SelectorList parent) {
        List<CombinatorStep> parentSteps = parentSteps(SelectorList.of(parentSelector));
        steps.addAll(parentSteps.subList(0, parentSteps.size() - 1));
        CombinatorStep last = parentSteps.get(parentSteps.size() - 1);

        List<SimpleSelector> simples = new ArrayList<>(last.compound().simples());
        List<SimpleSelector> rest = step.compound().simples();
        for (int index = 1; index < rest.size(); index++) {
            simples.addAll(substitute(rest.get(index), parent));
        }

        CompoundSelector merged = new CompoundSelector(simples, step.compound().span());
        steps.add(new CombinatorStep(last.combinator(), merged, step.span()));
    }

    private static CompoundSelector substitute(CompoundSelector compound, //
                                               SelectorList parent) {
        if (!compound.containsNestingSelector()) {
            return compound;
        }

        List<SimpleSelector> simples = new ArrayList<>(compound.simples().size());
        for (SimpleSelector simple : compound.simples()) {
            simples.addAll(substitute(simple, parent));
        }

        return new CompoundSelector(simples, compound.span());
    }

    /**
     * One simple selector, expanded into however many replace it, a {@code &} may become a
     * whole compound selector's worth.
     */
    private static List<SimpleSelector> substitute(SimpleSelector simple, //
                                                   SelectorList parent) {
        if (simple instanceof NestingSelector) {
            return parentAsSimples(parent);
        }

        if (simple instanceof PseudoClassSelector pseudo && pseudo.containsNestingSelector()) {
            List<ComplexSelector> inner = new ArrayList<>();

            for (ComplexSelector selector : pseudo.selectors().selectors()) {
                // Only the alternatives that mention '&'. An argument of :is() that does not
                // is not a relative selector and has no parent to be prepended to it.
                inner.add(selector.containsNestingSelector() ? substitute(selector, parent) : selector);
            }

            SelectorList substituted = new SelectorList(inner, pseudo.selectors().span());
            return List.of(new PseudoClassSelector(pseudo.name(),
                                                   pseudo.functional(),
                                                   pseudo.arguments(),
                                                   substituted,
                                                   pseudo.span()));
        }

        return List.of(simple);
    }

    /**
     * Cases 2 and 3: inline the parent's simple selectors, or wrap the list in {@code :is()}.
     */
    private static List<SimpleSelector> parentAsSimples(SelectorList parent) {
        if (!parent.isMultiple()) {
            ComplexSelector only = parent.selectors().get(0);
            if (only.steps().size() == 1 && isInlinable(only.first())) {
                return only.first().simples();
            }
        }

        return List.of(wrap(parent));
    }

    /**
     * Whether a compound selector can be dropped into the middle of another one.
     *
     * <p>A type selector cannot: it has to come first, and {@code .open} + {@code div} would
     * concatenate to {@code .opendiv}. A pseudo-element cannot either, since it has to come
     * last and nothing may follow it.
     */
    private static boolean isInlinable(CompoundSelector compound) {
        for (SimpleSelector simple : compound.simples()) {
            if (simple instanceof TypeSelector || simple instanceof PseudoElementSelector) {
                return false;
            }
        }

        return true;
    }

    /**
     * {@code :is(<parent>)}: specificity-preserving, which is why it is the fallback.
     */
    private static PseudoClassSelector wrap(SelectorList parent) {
        return new PseudoClassSelector("is", true, List.of(), parent, parent.span());
    }

    private NestingExpander() {
        // utility class
    }
}
