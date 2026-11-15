package bbb.hercules;

import com.google.common.collect.ImmutableList;
import lombok.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A finite, rooted, ordered tree with a value and immutable child subtrees.
 *
 * <p>This is a domain-neutral extraction from an earlier recursion-schemes
 * prototype. Guava {@link ImmutableList} is deliberately part of the public
 * API, as reserved by the Hercules dependency policy. It rejects null
 * child references, preserving structural integrity. Node values can be
 * explicitly nullable using JSpecify type-use annotations.
 *
 * <p>This record is shallowly immutable: mutable node values can still be
 * mutated by external code. Recursive record-generated equality and
 * {@code toString()} are not stack-safe for arbitrary depth, even though
 * {@link Morphisms} traversals use an iterative interpreter.
 *
 * @param <N> node value type
 * @param value the node's value
 * @param children ordered, non-null, immutable child subtrees
 */
public record Tree<N extends @Nullable Object>(
        N value,
        @NonNull ImmutableList<Tree<N>> children) {

    /**
     * Build a leaf, without any children.
     *
     * @param <N> node value type
     * @param value node value
     * @return new leaf
     */
    public static <N extends @Nullable Object> Tree<N> leaf(N value) {
        return new Tree<>(value, ImmutableList.of());
    }
}
