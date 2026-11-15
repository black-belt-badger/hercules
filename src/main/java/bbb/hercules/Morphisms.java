package bbb.hercules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import lombok.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Recursion schemes for finite rooted ordered trees: ana, cata and hylo.
 *
 * <p>These operations preserve the algebra of the original prototype while replacing recursive JVM calls with a single iterative
 * depth-first, post-order evaluation engine. Each node is expanded or
 * folded exactly once. Child positions are visited left to right, and
 * algebra calls occur only after all children have been reduced.
 *
 * <p>The coalgebra must unfold a finite tree. There is no cycle detection,
 * memoization, timeout or built-in work budget: a self-repeating coalgebra
 * does not terminate, even though it no longer overflows the Java stack.
 * Each callback invocation is synchronous; user exceptions propagate.
 *
 * <p>Hylomorphism fusion avoids constructing an intermediate {@link Tree},
 * not every intermediate {@link Layer} or collection. For n nodes, time
 * complexity is O(n), assuming constant-time algebra/coalgebra work and
 * counting each child slot once. Temporary traversal memory is O(d+w),
 * where d is active depth and w is the total number of child positions
 * held by active frames, at most O(n). Anamorphism also retains O(n)
 * output Tree nodes. The stack-safe engine does not rely on Trampoline,
 * but is compatible with pure Trampoline-based callers.
 *
 * <p>Node values may be explicitly nullable; structural child seeds,
 * child result slots and callback results must be non-null.
 */
public final class Morphisms {

    private Morphisms() {
    }

    /**
     * Unfold a tree by expanding each seed into a node and child seeds.
     *
     * <p>Coalgebra evaluation is pre-order, depth-first and left-to-right.
     * The returned tree is immutable and preserves child order.
     *
     * @param <N> node value type
     * @param <S> seed type
     * @param seed starting seed
     * @param coalgebra expands one seed into node value and child seeds
     * @return fully materialized immutable ordered tree
     */
    public static <N extends @Nullable Object, S> Tree<N> ana(
            @NonNull S seed,
            @NonNull Function<? super S, ? extends Layer<N, S>> coalgebra) {
        return hylo(seed, coalgebra, layer -> new Tree<>(layer.value(), layer.children()));
    }

    /**
     * Fold an existing tree from leaves to root using an algebra.
     *
     * <p>Children are reduced in encounter order before their parent.
     * This delegates to the same iterative engine as {@link #hylo}.
     * The source tree is not modified or cloned.
     *
     * @param <N> node value type
     * @param <R> result type
     * @param tree source immutable tree
     * @param algebra reduces one node and all its computed child results
     * @return final result at the root
     */
    public static <N extends @Nullable Object, R> R cata(
            @NonNull Tree<N> tree,
            @NonNull Function<? super Layer<N, R>, ? extends R> algebra) {
        return hylo(tree, node -> new Layer<>(node.value(), node.children()), algebra);
    }

    /**
     * Fuse unfolding and folding without materializing an intermediate Tree.
     *
     * <p>Each seed's coalgebra is evaluated once in pre-order, while its
     * algebra runs once in post-order. Child results are delivered left to
     * right in an immutable layer. Unlike separate {@code cata(ana(...))},
     * both callbacks are interleaved; observable equality assumes pure
     * deterministic callbacks. A null layer, child or algebra result fails
     * when reached rather than being interpreted as an absent subtree.
     *
     * @param <N> node value type
     * @param <S> seed type
     * @param <R> reduction result type
     * @param seed root seed
     * @param coalgebra expands seeds into node values and child seeds
     * @param algebra reduces node values and computed child results
     * @return final reduced result, never null
     */
    public static <N extends @Nullable Object, S, R> R hylo(
            @NonNull S seed,
            @NonNull Function<? super S, ? extends Layer<N, S>> coalgebra,
            @NonNull Function<? super Layer<N, R>, ? extends R> algebra) {
        ArrayDeque<Frame<N, S, R>> frames = new ArrayDeque<>();
        frames.push(expand(seed, coalgebra));

        while (true) {
            Frame<N, S, R> current = frames.peek();
            if (current == null) {
                throw new IllegalStateException("No current recursion frame");
            }

            if (current.nextChild < current.layer.children().size()) {
                S childSeed = current.layer.children().get(current.nextChild++);
                frames.push(expand(childSeed, coalgebra));
                continue;
            }

            R reduced = Objects.requireNonNull(
                    algebra.apply(new Layer<>(current.layer.value(),
                            ImmutableList.copyOf(current.results))),
                    "Algebra returned null result");
            frames.pop();

            if (frames.isEmpty()) {
                return reduced;
            }
            frames.peek().results.add(reduced);
        }
    }

    private static <N extends @Nullable Object, S, R> Frame<N, S, R> expand(
            S seed, Function<? super S, ? extends Layer<N, S>> coalgebra) {
        return new Frame<>(Objects.requireNonNull(
                coalgebra.apply(seed), "Coalgebra returned null Layer"));
    }

    private static final class Frame<N extends @Nullable Object, S, R> {
        private final Layer<N, S> layer;
        private final ArrayList<R> results;
        private int nextChild;

        private Frame(Layer<N, S> layer) {
            this.layer = layer;
            this.results = new ArrayList<>(layer.children().size());
        }
    }
}
