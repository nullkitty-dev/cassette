package dev.nullkitty.cassette.parser;

import java.util.Arrays;
import java.util.List;

import dev.nullkitty.cassette.ast.WhitespaceToken;

/**
 * The scratch space both parsers build their child lists in, so that a finished list costs one
 * array and one list object instead of four allocations.
 *
 * <p>The path this replaces is {@code new ArrayList<>()} per list, handed to an AST record whose
 * canonical constructor calls {@code List.copyOf}. That allocates the {@code ArrayList}, its
 * backing array, a second one if the default capacity of ten was not enough, the array
 * {@code toArray()} copies out of it, another because {@code List.of(E[])} does not trust an array
 * it did not create, and the list object: 144 bytes for a three-element list against the 56 the
 * result occupies.
 *
 * <p>{@code List.copyOf} returns its argument unchanged when that argument is already one of
 * {@code List.of}'s own implementations. The records therefore keep their {@code copyOf}, so a
 * caller handing one a mutable list still gets an immutable node, and the parser pays nothing for
 * it. The {@code List.of(e1, …, eN)} overloads up to ten arguments adopt the varargs array the
 * compiler created at the call site rather than copying it; past ten there is no such overload and
 * {@link #materialize} pays for one copy, which 99.4% of the lists in Bootstrap and 99.8% of those
 * in Tailwind never reach.
 *
 * <p>Every push must be matched by a {@link #take}, {@link #takeTrimmed} or {@link #reset} against
 * the same mark. Both parsers build strictly innermost first, since a declaration's value is
 * finished before the style block holding it, so the discipline is a stack. An abandoned build is
 * the hazard, because a selector that fails half way through has already pushed: those paths
 * funnel through wrappers in {@link SelectorParser} that reset, and
 * {@link Parser#parseStylesheet()} asserts the stack came back empty, which turns a leak into a
 * test failure rather than a neighbouring rule quietly growing a child.
 */
final class NodeStack {

    /**
     * Deep enough for real CSS to never grow it; a stylesheet's own child list is the outlier.
     */
    private static final int INITIAL_CAPACITY = 128;

    private Object[] items = new Object[INITIAL_CAPACITY];

    private int top;

    /**
     * Where the list being started here begins; pass it back to whatever ends the list.
     */
    int mark() {
        return this.top;
    }

    void push(Object item) {
        if (this.top == this.items.length) {
            this.items = Arrays.copyOf(this.items, this.top * 2);
        }

        this.items[this.top++] = item;
    }

    /**
     * Pushes a node the parser may have dropped, which is how a recovered rule vanishes.
     */
    void pushIfPresent(Object item) {
        if (item != null) {
            push(item);
        }
    }

    /**
     * How many values have been pushed since {@code mark}.
     */
    int size(int mark) {
        return this.top - mark;
    }

    /**
     * The value at an absolute index, for the scans that read a partly-built list.
     */
    Object get(int index) {
        return this.items[index];
    }

    /**
     * Whether nothing has been pushed since {@code mark}.
     */
    boolean isEmpty(int mark) {
        return this.top == mark;
    }

    /**
     * Discards everything pushed since {@code mark}, for a build that was abandoned.
     *
     * <p>Clears the slots as it goes, unlike {@link #take}: what is being dropped here is
     * genuinely garbage, where everything {@code take} leaves behind is in the tree anyway.
     *
     * @param mark the mark the abandoned build started at
     */
    void reset(int mark) {
        Arrays.fill(this.items, mark, this.top, null);

        this.top = mark;
    }

    /**
     * Drops everything from {@code newTop} up, keeping the list being built.
     *
     * <p>For {@code !important}, which is recognized only once the value that precedes it has
     * been consumed and is then cut back off the end.
     *
     * @param newTop the absolute index to truncate to
     */
    void truncate(int newTop) {
        Arrays.fill(this.items, newTop, this.top, null);

        this.top = newTop;
    }

    /**
     * Ends the list started at {@code mark}.
     *
     * @param <E>  the element type, which the caller knows and this class does not
     * @param mark the mark the list started at
     * @return the finished list, immutable and safe to hand straight to an AST record
     */
    <E> List<E> take(int mark) {
        List<E> list = this.<E> materialize(mark, this.top);

        this.top = mark;

        return list;
    }

    /**
     * Ends the list started at {@code mark}, dropping whitespace from both ends of it.
     *
     * <p>Whitespace between values is meaningful, {@code 1px solid} is two values and the gap
     * says so, but whitespace at the edges of a value or a prelude never is. Trimming here rather
     * than on a finished list avoids a second allocation.
     *
     * @param <E>  the element type
     * @param mark the mark the list started at
     * @return the finished list, trimmed
     */
    <E> List<E> takeTrimmed(int mark) {
        int from = mark;
        int to = this.top;

        while (from < to && this.items[from] instanceof WhitespaceToken) {
            from++;
        }

        while (to > from && this.items[to - 1] instanceof WhitespaceToken) {
            to--;
        }

        List<E> list = this.<E> materialize(from, to);

        this.top = mark;

        return list;
    }

    /**
     * Builds the immutable list holding {@code [from, to)}.
     *
     * <p>The ladder is not a style choice. {@code List.of} has a fixed-arity overload for every
     * length up to ten, each of which adopts the varargs array the compiler builds here; the
     * array-taking overload copies, because an array it did not create could still be held and
     * mutated by whoever passed it. Naming the elements is how this code says the array is its
     * own.
     */
    @SuppressWarnings("unchecked") // the caller's E is what it pushed; nothing else can be here
    private <E> List<E> materialize(int from, int to) {
        Object[] a = this.items;
        return switch (to - from) {
            case 0 -> List.of();
            case 1 -> List.of((E) a[from]);
            case 2 -> List.of((E) a[from], (E) a[from + 1]);
            case 3 -> List.of((E) a[from], (E) a[from + 1], (E) a[from + 2]);
            case 4 -> List.of((E) a[from], (E) a[from + 1], (E) a[from + 2], (E) a[from + 3]);
            case 5 -> List.of((E) a[from], (E) a[from + 1], (E) a[from + 2], (E) a[from + 3], (E) a[from + 4]);

            case 6 -> List.of((E) a[from],
                              (E) a[from + 1],
                              (E) a[from + 2],
                              (E) a[from + 3],
                              (E) a[from + 4],
                              (E) a[from + 5]);

            case 7 -> List.of((E) a[from],
                              (E) a[from + 1],
                              (E) a[from + 2],
                              (E) a[from + 3],
                              (E) a[from + 4],
                              (E) a[from + 5],
                              (E) a[from + 6]);

            case 8 -> List.of((E) a[from],
                              (E) a[from + 1],
                              (E) a[from + 2],
                              (E) a[from + 3],
                              (E) a[from + 4],
                              (E) a[from + 5],
                              (E) a[from + 6],
                              (E) a[from + 7]);

            case 9 -> List.of((E) a[from],
                              (E) a[from + 1],
                              (E) a[from + 2],
                              (E) a[from + 3],
                              (E) a[from + 4],
                              (E) a[from + 5],
                              (E) a[from + 6],
                              (E) a[from + 7],
                              (E) a[from + 8]);

            case 10 -> List.of((E) a[from],
                               (E) a[from + 1],
                               (E) a[from + 2],
                               (E) a[from + 3],
                               (E) a[from + 4],
                               (E) a[from + 5],
                               (E) a[from + 6],
                               (E) a[from + 7],
                               (E) a[from + 8],
                               (E) a[from + 9]);

            default -> (List<E>) List.of(Arrays.copyOfRange(a, from, to));
        };
    }
}
