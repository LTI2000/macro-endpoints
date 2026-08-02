package io.macroapi.structure;

import io.macroapi.hkt.Higher;
import io.macroapi.hkt.Applicative;
import io.macroapi.hkt.Traverse;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The pattern functor of a rose tree: one layer is a labelled node with any number of holes for its
 * children.
 *
 * <p>This is the shape of every <em>hierarchical</em> remote traversal — a category taxonomy, an
 * organisation chart, a threaded discussion, a dependency closure. Unlike {@link ListF}, a layer
 * has many recursive positions, so an effectful traversal of it can expand all children at once;
 * with the parallel applicative of the effect type, an entire tree level is fetched concurrently
 * without a word of concurrency code at the call site.</p>
 *
 * @param <E> the label type carried by each node
 * @param <A> the recursive position
 */
public sealed interface TreeF<E, A> extends Higher<TreeF.Witness<E>, A> {

    /**
     * Uninhabited type-level tag standing for the partially applied constructor {@code TreeF<E, _>}.
     *
     * @param <E> the label type held fixed by the partial application
     */
    final class Witness<E> {
        private Witness() {
            throw new AssertionError("no instances");
        }
    }

    /**
     * A labelled node with its children in the recursive positions.
     *
     * <p>A leaf is simply a node with an empty child list, so no separate variant is needed.</p>
     *
     * @param label    the value at this node
     * @param children the recursive positions
     * @param <E>      the label type
     * @param <A>      the recursive position
     */
    record Node<E, A>(E label, List<A> children) implements TreeF<E, A> {
        /**
         * Canonical constructor; defensively copies the child list.
         *
         * @param label    the node value
         * @param children the child positions
         */
        public Node {
            Objects.requireNonNull(children, "children");
            children = List.copyOf(children);
        }
    }

    /**
     * Recovers the concrete type from its {@link Higher} encoding; see {@link Higher} for why this cast is
     * safe.
     *
     * @param higher the encoded layer
     * @param <E> the label type
     * @param <A> the recursive position
     * @return the same value, statically typed
     */
    @SuppressWarnings("unchecked")
    static <E, A> TreeF<E, A> narrow(Higher<Witness<E>, A> higher) {
        return (TreeF<E, A>) higher;
    }

    /**
     * The traversable instance for trees of a fixed label type.
     *
     * <p>Children are combined through {@link Applicative#sequence}, so with a parallel applicative
     * all siblings are visited concurrently while their relative order is preserved.</p>
     *
     * @param <E> the label type
     * @return the instance
     */
    static <E> Traverse<Witness<E>> traversal() {
        return new Traverse<Witness<E>>() {
            @Override
            public <A, B> Higher<Witness<E>, B> map(Higher<Witness<E>, A> fa, Function<? super A, ? extends B> fn) {
                return switch (narrow(fa)) {
                    case Node<E, A>(var label, var children) ->
                            new Node<>(label, children.stream().<B>map(fn::apply).toList());
                };
            }

            @Override
            public <G, A, B> Higher<G, Higher<Witness<E>, B>> traverse(Applicative<G> applicative,
                                                                       Higher<Witness<E>, A> fa,
                                                                       Function<? super A, ? extends Higher<G, B>> fn) {
                return switch (narrow(fa)) {
                    case Node<E, A>(var label, var children) -> {
                        List<Higher<G, B>> visited = children.stream().<Higher<G, B>>map(fn::apply).toList();
                        yield applicative.<List<B>, Higher<Witness<E>, B>>map(
                                applicative.sequence(visited),
                                reduced -> new Node<>(label, reduced));
                    }
                };
            }
        };
    }
}
