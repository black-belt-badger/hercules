package bbb.hercules.consumer;

import bbb.hercules.Layer;
import bbb.hercules.Morphisms;
import bbb.hercules.Tree;
import bbb.hercules.Trees;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent-package consumer uses only the public algebra and Guava
 * ImmutableList deliberately exposed in the supported Hercules API.
 */
class MorphismsPublicApiTest {

    record Section(String title, int depth) { }

    private static Layer<String, Section> expand(Section seed) {
        return new Layer<>(seed.title(), seed.depth() == 0
                ? ImmutableList.of()
                : ImmutableList.of(
                        new Section(seed.title() + ".A", seed.depth() - 1),
                        new Section(seed.title() + ".B", seed.depth() - 1)));
    }

    @Test
    void buildsAndFoldsAStandaloneOutlineWithoutApplicationDependencies() {
        Section root = new Section("chapter", 2);
        Tree<String> outline = Morphisms.ana(root, MorphismsPublicApiTest::expand);
        Function<Layer<String, Integer>, Integer> count = layer ->
                1 + layer.children().stream().mapToInt(Integer::intValue).sum();

        assertEquals(7, Morphisms.cata(outline, count));
        assertEquals(7, Morphisms.hylo(root, MorphismsPublicApiTest::expand, count));
        assertEquals(List.of("chapter.A", "chapter.B"),
                outline.children().stream().map(Tree::value).toList());
        assertEquals("chapter\n├── chapter.A\n│   ├── chapter.A.A\n"
                        + "│   └── chapter.A.B\n└── chapter.B\n"
                        + "    ├── chapter.B.A\n    └── chapter.B.B\n",
                Trees.prettyPrint(outline));
    }

    @Test
    void producerAndReducerAreCalledPreciselyOncePerNode() {
        AtomicInteger expansions = new AtomicInteger();
        AtomicInteger reductions = new AtomicInteger();
        List<String> trace = new ArrayList<>();

        Function<Section, Layer<String, Section>> coalgebra = seed -> {
            expansions.incrementAndGet();
            trace.add("enter " + seed.title());
            return expand(seed);
        };
        Function<Layer<String, Integer>, Integer> algebra = layer -> {
            reductions.incrementAndGet();
            trace.add("exit " + layer.value());
            return 1 + layer.children().stream().mapToInt(Integer::intValue).sum();
        };

        assertEquals(Integer.valueOf(3),
                Morphisms.hylo(new Section("root", 1), coalgebra, algebra));
        assertEquals(3, expansions.get());
        assertEquals(3, reductions.get());
        assertEquals(List.of("enter root", "enter root.A", "exit root.A",
                "enter root.B", "exit root.B", "exit root"), trace);
    }
}
