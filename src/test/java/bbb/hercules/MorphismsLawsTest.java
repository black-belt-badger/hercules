package bbb.hercules;

import com.google.common.collect.ImmutableList;
import org.instancio.junit.Given;
import org.instancio.junit.InstancioExtension;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(InstancioExtension.class)
class MorphismsLawsTest {

    private record Seed(int value, int depth) {
    }

    private static Layer<Integer, Seed> expandBinary(Seed seed) {
        return new Layer<>(seed.value(), seed.depth() == 0
                ? ImmutableList.of()
                : ImmutableList.of(
                        new Seed(seed.value() + 1, seed.depth() - 1),
                        new Seed(seed.value() + 2, seed.depth() - 1)));
    }

    private static Long sum(Layer<Integer, Long> layer) {
        return layer.value().longValue()
                + layer.children().stream().mapToLong(Long::longValue).sum();
    }

    private static Integer count(Layer<Integer, Integer> layer) {
        return 1 + layer.children().stream().mapToInt(Integer::intValue).sum();
    }

    @RepeatedTest(96)
    void layerFunctorIdentityAndCompositionForGeneratedValues(
            @Given Integer value, @Given Integer first, @Given Integer second,
            @Given Integer third) {
        Layer<Integer, Integer> layer =
                new Layer<>(value, ImmutableList.of(first, second, third));
        Function<Integer, Integer> f = x -> x * 3;
        Function<Integer, String> g = x -> "value=" + x;

        assertEquals(layer, layer.map(Function.identity()));
        assertEquals(layer.map(f).map(g), layer.map(f.andThen(g)));
        assertEquals(value, layer.map(f).value());
        assertEquals(ImmutableList.of(f.apply(first), f.apply(second), f.apply(third)),
                layer.map(f).children());
    }

    @RepeatedTest(96)
    void fusionEqualsFoldingAnUnfoldedFiniteTree(
            @Given Integer generated, @Given Integer generatedDepth) {
        Seed seed = new Seed(Math.floorMod(generated, 101),
                Math.floorMod(generatedDepth, 7));
        Tree<Integer> materialized = Morphisms.ana(seed, MorphismsLawsTest::expandBinary);
        Long viaTree = Morphisms.cata(materialized, MorphismsLawsTest::sum);
        Long fused = Morphisms.hylo(seed, MorphismsLawsTest::expandBinary,
                MorphismsLawsTest::sum);

        assertEquals(viaTree, fused);
        assertEquals((1 << (seed.depth() + 1)) - 1,
                Morphisms.cata(materialized, MorphismsLawsTest::count));
        assertEquals(seed.value(), materialized.value());
        assertEquals(seed.depth() == 0 ? 0 : 2, materialized.children().size());
    }

    @Test
    void originalFiveNodeWalkthroughIsPreserved() {
        Function<Integer, Layer<Integer, Integer>> coalgebra = node -> new Layer<>(
                node, switch (node) {
                    case 1 -> ImmutableList.of(2, 3);
                    case 2 -> ImmutableList.of(4, 5);
                    default -> ImmutableList.of();
                });
        Tree<Integer> expected = new Tree<>(1, ImmutableList.of(
                new Tree<>(2, ImmutableList.of(Tree.leaf(4), Tree.leaf(5))),
                Tree.leaf(3)));
        Tree<Integer> unfolded = Morphisms.ana(1, coalgebra);
        assertEquals(expected, unfolded);
        assertEquals("1\n├── 2\n│   ├── 4\n│   └── 5\n└── 3\n",
                Trees.prettyPrint(unfolded));
        Function<Layer<Integer, Integer>, Integer> sumInt = layer ->
                layer.value()
                        + layer.children().stream().mapToInt(Integer::intValue).sum();
        assertEquals(Integer.valueOf(15), Morphisms.cata(unfolded, sumInt));

        List<Layer<Integer, Integer>> reductionLayers = new ArrayList<>();
        Function<Layer<Integer, Integer>, Integer> recordedSum = layer -> {
            reductionLayers.add(layer);
            return sumInt.apply(layer);
        };
        assertEquals(Integer.valueOf(15), Morphisms.cata(unfolded, recordedSum));
        assertEquals(List.of(
                new Layer<>(4, ImmutableList.of()),
                new Layer<>(5, ImmutableList.of()),
                new Layer<>(2, ImmutableList.of(4, 5)),
                new Layer<>(3, ImmutableList.of()),
                new Layer<>(1, ImmutableList.of(11, 3))), reductionLayers);
    }

    @Test
    void hyloInterleavesExpansionAndReductionInDepthFirstOrder() {
        List<String> trace = new ArrayList<>();
        Function<Integer, Layer<Integer, Integer>> coalg = value -> {
            trace.add("expand " + value);
            return new Layer<>(value, switch (value) {
                case 1 -> ImmutableList.of(2, 3);
                case 2 -> ImmutableList.of(4, 5);
                default -> ImmutableList.of();
            });
        };
        Function<Layer<Integer, Integer>, Integer> alg = layer -> {
            int result = layer.value()
                    + layer.children().stream().mapToInt(Integer::intValue).sum();
            trace.add("reduce " + layer.value() + " -> " + result);
            return result;
        };

        assertEquals(15, Morphisms.hylo(1, coalg, alg));
        assertEquals(List.of(
                "expand 1", "expand 2", "expand 4", "reduce 4 -> 4",
                "expand 5", "reduce 5 -> 5", "reduce 2 -> 11",
                "expand 3", "reduce 3 -> 3", "reduce 1 -> 15"), trace);
    }

    @Test
    void expansionCompletesBeforeSeparateFoldingUnlikeFusedHylo() {
        List<String> trace = new ArrayList<>();
        Function<Integer, Layer<Integer, Integer>> coalg = value -> {
            trace.add("expand " + value);
            return new Layer<>(value, value == 1
                    ? ImmutableList.of(2, 3) : ImmutableList.of());
        };
        Function<Layer<Integer, Integer>, Integer> alg = layer -> {
            trace.add("reduce " + layer.value());
            return layer.value() + layer.children().stream().mapToInt(Integer::intValue).sum();
        };
        assertEquals(Integer.valueOf(6), Morphisms.cata(Morphisms.ana(1, coalg), alg));
        assertEquals(List.of(
                "expand 1", "expand 2", "expand 3",
                "reduce 2", "reduce 3", "reduce 1"), trace);
        trace.clear();
        assertEquals(Integer.valueOf(6), Morphisms.hylo(1, coalg, alg));
        assertEquals(List.of(
                "expand 1", "expand 2", "reduce 2",
                "expand 3", "reduce 3", "reduce 1"), trace);
    }

    @Test
    void leavesAndDifferentNodeTypesAreSupported() {
        Tree<String> leaf = Morphisms.ana("leaf",
                seed -> new Layer<>(seed, ImmutableList.of()));
        assertEquals(Tree.leaf("leaf"), leaf);
        assertEquals("leaf", Morphisms.cata(leaf, Layer::value));
        assertEquals("leaf", Morphisms.hylo("leaf",
                seed -> new Layer<>(seed, ImmutableList.of()), Layer::value));

        record Named(String label, int weight) {
        }
        Tree<Named> named = new Tree<>(
                new Named("root", 10),
                ImmutableList.of(Tree.leaf(new Named("child", 20))));
        Function<Layer<Named, Long>, Long> weightedSum = layer ->
                layer.value().weight()
                        + layer.children().stream().mapToLong(Long::longValue).sum();
        assertEquals(Long.valueOf(30), Morphisms.cata(named, weightedSum));
        assertEquals("root\n└── child\n", Trees.prettyPrint(named, Named::label));
    }

    @Test
    void structuralCollectionsAreImmutableAndPreserveInputIdentity() {
        ImmutableList<Tree<String>> children = ImmutableList.of(Tree.leaf("first"),
                Tree.leaf("second"));
        Tree<String> tree = new Tree<>("root", children);
        assertSame(children, tree.children(), "existing ImmutableList remains directly available");
        assertThrows(UnsupportedOperationException.class,
                () -> tree.children().add(Tree.leaf("third")));
        Layer<String, Integer> layer = new Layer<>("root", ImmutableList.of(2, 3));
        assertEquals(ImmutableList.of(20, 30), layer.map(n -> n * 10).children());
        assertThrows(UnsupportedOperationException.class,
                () -> layer.children().add(4));
    }

    @Test
    void structuralNullsAreRejectedAndCallbacksFailLazilyWhenReached() {
        assertThrows(NullPointerException.class,
                () -> new Tree<>("bad", null));
        assertThrows(NullPointerException.class,
                () -> new Layer<>("bad", null));
        assertThrows(NullPointerException.class, () ->
                ImmutableList.<Tree<String>>of((Tree<String>) null));
        assertThrows(NullPointerException.class,
                () -> new Layer<>("root", ImmutableList.of()).map(null));
        assertThrows(NullPointerException.class, () -> Morphisms.ana(1, null));
        assertThrows(NullPointerException.class, () -> Morphisms.cata(Tree.leaf(1), null));
        assertThrows(NullPointerException.class, () -> Morphisms.hylo(1,
                s -> new Layer<>(s, ImmutableList.of()), null));
        assertThrows(NullPointerException.class,
                () -> Morphisms.ana(1, s -> null));
        assertThrows(NullPointerException.class, () ->
                Morphisms.cata(Tree.leaf(1), layer -> null));
        assertThrows(NullPointerException.class, () ->
                new Layer<>("x", ImmutableList.of(1)).map(x -> null));
    }

    @Test
    void userExceptionsPropagateUnchangedWithNoPartialRecovery() {
        var failed = new IllegalStateException("from algebra");
        assertSame(failed, assertThrows(IllegalStateException.class, () ->
                Morphisms.cata(Tree.leaf("one"), layer -> {
                    throw failed;
                })));
        assertSame(failed, assertThrows(IllegalStateException.class, () ->
                Morphisms.ana("one", seed -> { throw failed; })));
        assertSame(failed, assertThrows(IllegalStateException.class, () ->
                Morphisms.hylo("one", seed -> new Layer<>(seed, ImmutableList.of()),
                        layer -> { throw failed; })));
    }

    @Test
    void explicitlyNullableNodeValuesAreDistinctFromNullStructure() {
        Tree<@Nullable String> tree = Morphisms.ana("root",
                seed -> new Layer<@Nullable String, String>(null,
                        ImmutableList.of()));
        assertNull(tree.value());
        Function<Layer<@Nullable String, Integer>, Integer> oneNode = layer -> 1;
        assertEquals(Integer.valueOf(1), Morphisms.cata(tree, oneNode));
        assertEquals("null\n", Trees.prettyPrint(tree));
    }

    @Test
    void veryDeepUnbalancedTreeIsStackSafeForAllThreeSchemes() {
        int depth = 110_000;
        Function<Integer, Layer<Integer, Integer>> coalg = n ->
                new Layer<>(n, n == 0 ? ImmutableList.of() : ImmutableList.of(n - 1));
        Function<Layer<Integer, Long>, Long> alg = layer ->
                1L + layer.children().stream().mapToLong(Long::longValue).sum();

        assertEquals((long) depth + 1, Morphisms.hylo(depth, coalg, alg));
        Tree<Integer> tree = Morphisms.ana(depth, coalg);
        assertEquals((long) depth + 1, Morphisms.cata(tree, alg));

        Tree<Integer> cursor = tree;
        for (int remaining = depth; remaining >= 0; remaining--) {
            assertEquals(remaining, cursor.value());
            if (remaining > 0) {
                cursor = cursor.children().get(0);
            }
        }
    }

    @Test
    void trampolineCanDeferEntireTraversalWithoutMakingItRecursive() {
        Trampoline<Long> program = Trampoline.more(() ->
                Trampoline.done(Morphisms.hylo(100_000,
                        n -> new Layer<>(n, n == 0
                                ? ImmutableList.of() : ImmutableList.of(n - 1)),
                        layer -> 1L + layer.children().stream().mapToLong(Long::longValue).sum())));
        assertEquals(100_001L, program.run());
    }
}
