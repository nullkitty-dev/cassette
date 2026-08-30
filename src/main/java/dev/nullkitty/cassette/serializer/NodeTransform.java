package dev.nullkitty.cassette.serializer;

import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

import dev.nullkitty.cassette.ast.Node;

/**
 * One optimization: a pure function from a node to its replacement.
 *
 * <p>A transform never walks the tree itself. {@link Optimizer} does that once, for all of
 * them at once, and hands each transform the node types it asked for, which is what keeps
 * N enabled optimizations to one tree rebuild rather than N.
 *
 * <p>Returning the node unchanged is how a transform declines: the driver compares by
 * identity, so an unchanged subtree is never reallocated.
 *
 * @param <T> the widest node type this transform accepts, and returns
 */
public interface NodeTransform<T extends Node> {

    /**
     * The exact node classes this transform wants to see.
     *
     * <p>Exact, not assignable: a transform declaring {@code Declaration.class} is offered
     * declarations and nothing else. Naming a class the driver does not walk is an error
     * rather than a silent no-op, see {@link Optimizer}.
     *
     * @return the node classes to be offered
     */
    Set<Class<? extends T>> types();

    /**
     * Rewrites one node.
     *
     * <p>Children have already been rewritten when this is called, so a transform sees the
     * result of everything below it. The returned node must be assignable to the position
     * the original occupied; a component value may become a different component value, but
     * not a rule.
     *
     * @param node the node to rewrite
     * @return the replacement, or {@code node} itself to decline
     */
    T apply(T node);

    /**
     * A transform over a single node type.
     *
     * @param type      the node class to be offered
     * @param operation what to do with it
     * @param <T>       the node type
     * @return the transform
     */
    static <T extends Node> NodeTransform<T> of(Class<T> type, //
                                                UnaryOperator<T> operation) {
        return of(Set.<Class<? extends T>> of(type), operation);
    }

    /**
     * A transform over several node types at once, for optimizations that treat them alike,
     * the three numeric tokens, say.
     *
     * @param types     the node classes to be offered
     * @param operation what to do with them
     * @param <T>       a type every one of {@code types} is assignable to
     * @return the transform
     */
    static <T extends Node> NodeTransform<T> of(Set<Class<? extends T>> types, //
                                                UnaryOperator<T> operation) {
        Set<Class<? extends T>> declared = Set.copyOf(types);
        Objects.requireNonNull(operation, "operation");

        if (declared.isEmpty()) {
            throw new IllegalArgumentException("a transform must declare at least one node type");
        }

        return new NodeTransform<>() {

            @Override
            public Set<Class<? extends T>> types() {
                return declared;
            }

            @Override
            public T apply(T node) {
                return operation.apply(node);
            }
        };
    }
}
