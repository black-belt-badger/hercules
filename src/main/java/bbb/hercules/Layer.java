package bbb.hercules;

import java.util.Objects;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import lombok.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One nonrecursive layer of an ordered tree: a node value and child slots.
 *
 * <p>The child slots can contain seeds during an anamorphism, subtrees
 * during a catamorphism, or computed results during a hylomorphism.
 * {@link #map(Function)} changes only child slots, preserving the node
 * value and their order. The shape functor has identity and composition
 * laws for pure, deterministic mappers.
 *
 * <p>Null child slots are not permitted by Guava {@link ImmutableList}.
 * Node values may be explicitly nullable via JSpecify. No method performs
 * I/O or changes the source layer.
 *
 * @param <N> node value type
 * @param <C> child slot type
 * @param value the node's value
 * @param children ordered immutable child slots
 */
public record Layer<N extends @Nullable Object, C>(
        N value,
        @NonNull ImmutableList<C> children) {

    /**
     * Transform each child once, left to right, without changing the parent.
     *
     * <p>The mapper is structurally checked eagerly. Its exceptions escape
     * unchanged, and a null child result is rejected when encountered.
     * No attempt is made to recover partial mapped output.
     *
     * @param <R> new child slot type
     * @param mapper required child transformation
     * @return an immutable layer with transformed children
     */
    public <R> Layer<N, R> map(@NonNull Function<? super C, ? extends R> mapper) {
        ImmutableList.Builder<R> mapped = ImmutableList.builderWithExpectedSize(children.size());
        for (C child : children) {
            mapped.add(Objects.requireNonNull(mapper.apply(child),
                    "Layer.map returned null child"));
        }
        return new Layer<>(value, mapped.build());
    }
}
