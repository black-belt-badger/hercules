package bbb.hercules;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Function;

import lombok.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Optional, deterministic pretty-printing for immutable ordered trees.
 *
 * <p>Uses an iterative depth-first traversal to avoid Java recursion.
 * Every node occupies one line. Building a fully indented string is
 * naturally output-size-dependent and may require O(n * depth) characters;
 * this is a teaching/debugging utility, not a bounded logging backend.
 */
public final class Trees {

    private Trees() {
    }

    /**
     * Render node values using String.valueOf, including nullable values.
     *
     * @param <N> node value type
     * @param tree root tree
     * @return indented visualization ending with a newline
     */
    public static <N extends @Nullable Object> String prettyPrint(@NonNull Tree<N> tree) {
        return prettyPrint(tree, String::valueOf);
    }

    /**
     * Render a tree with a caller-supplied node description.
     *
     * <p>Nodes are described once in pre-order, left to right. Callback
     * exceptions propagate unchanged; a null description is rejected.
     *
     * @param <N> node value type
     * @param tree root tree
     * @param describe required value-to-text function
     * @return indented visualization
     */
    public static <N extends @Nullable Object> String prettyPrint(
            @NonNull Tree<N> tree,
            @NonNull Function<? super N, String> describe) {
        StringBuilder output = new StringBuilder();
        output.append(Objects.requireNonNull(describe.apply(tree.value()),
                "Tree description returned null")).append('\n');

        ArrayDeque<Entry<N>> stack = new ArrayDeque<>();
        addChildren(stack, tree, "");

        while (!stack.isEmpty()) {
            Entry<N> next = stack.pop();
            output.append(next.prefix()).append(next.last() ? "└── " : "├── ");
            output.append(Objects.requireNonNull(describe.apply(next.tree().value()),
                    "Tree description returned null")).append('\n');
            addChildren(stack, next.tree(),
                    next.prefix() + (next.last() ? "    " : "│   "));
        }
        return output.toString();
    }

    private static <N extends @Nullable Object> void addChildren(
            ArrayDeque<Entry<N>> stack, Tree<N> tree, String prefix) {
        int length = tree.children().size();
        for (int index = length - 1; index >= 0; index--) {
            stack.push(new Entry<>(tree.children().get(index), prefix, index == length - 1));
        }
    }

    private record Entry<N extends @Nullable Object>(Tree<N> tree, String prefix, boolean last) {
    }
}
