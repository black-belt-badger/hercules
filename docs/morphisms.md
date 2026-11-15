# Recursion schemes: unfold, fold and fusion

Hercules provides **immutable ordered tree recursion schemes** extracted
and adapted from an earlier working prototype. The reusable classes
are **Tree**, **Layer**, **Morphisms** and **Trees.prettyPrint**, in
package bbb.hercules. No application-specific layouts, filesystem types
or service dependencies are included.

The public Guava **ImmutableList** child type is preserved deliberately,
as required by the Hercules [dependency policy](dependencies.md).
The original semantics are retained, but recursive Java method calls
have been replaced by an **iterative post-order interpreter**.

## Tree versus Layer

A Tree<N> is a complete recursive tree: its child slots contain
Tree<N> nodes. A Layer<N,C> is a *single node layer*, where child slots
can be seeds, subtrees or already-computed results.

~~~java
Tree<Integer> leaf = Tree.leaf(4);
Layer<String,Integer> layer =
        new Layer<>("directory", ImmutableList.of(2, 3));
Layer<String,String> labels = layer.map(n -> "child-" + n);
~~~

Layer.map changes **only** the child slots in order and preserves the
parent value. With pure total functions it satisfies the functor laws:

~~~text
layer.map(identity())             == layer
layer.map(f).map(g)               == layer.map(f.andThen(g))
~~~

The records are shallowly immutable. Guava ImmutableList rejects
null structural child references. Node values may be explicitly
nullable through JSpecify type-use annotations; mapped children and
algebra results must be non-null. Tree's generated record equality,
hashCode and toString are recursive and can themselves overflow on
extremely deep trees, even though Morphisms traversal is stack-safe.

## A single five-node walkthrough

~~~text
1
├── 2
│   ├── 4
│   └── 5
└── 3
~~~

**Anamorphism (ana)** unfolds seeds by invoking a coalgebra once per
node, in depth-first, left-to-right **pre-order**:

~~~java
Function<Integer, Layer<Integer,Integer>> expand = seed ->
        new Layer<>(seed, switch (seed) {
            case 1 -> ImmutableList.of(2, 3);
            case 2 -> ImmutableList.of(4, 5);
            default -> ImmutableList.of();
        });

Tree<Integer> tree = Morphisms.ana(1, expand);
String text = Trees.prettyPrint(tree);
~~~

The coalgebra describes one layer of child **seeds**; it never
constructs Tree objects. The visiting order is 1, 2, 4, 5, 3.
An empty list of child seeds denotes a leaf.

**Catamorphism (cata)** folds a completed tree. Each parent algebra
receives already-reduced child **results**, in post-order:

~~~java
Function<Layer<Integer,Integer>, Integer> sum = layer ->
        layer.value()
                + layer.children().stream().mapToInt(Integer::intValue).sum();

Integer value = Morphisms.cata(tree, sum); // 15
~~~

The algebra sees these layers in sequence:

| Node | Child results | Result |
| --- | --- | --- |
| 4 | [] | 4 |
| 5 | [] | 5 |
| 2 | [4, 5] | 11 |
| 3 | [] | 3 |
| 1 | [11, 3] | 15 |

**Hylomorphism (hylo)** fuses expansion and reduction without
retaining an intermediate Tree:

~~~java
Integer result = Morphisms.hylo(1, expand, sum); // 15
~~~

For a finite tree and pure deterministic callbacks:

~~~text
hylo(seed, coalgebra, algebra)
    == cata(ana(seed, coalgebra), algebra)
~~~

This law concerns **results, not effect traces**. In hylo,
pre-order coalgebra calls and post-order algebra calls interleave:

~~~text
expand 1
expand 2
expand 4
reduce 4 -> 4
expand 5
reduce 5 -> 5
reduce 2 -> 11
expand 3
reduce 3 -> 3
reduce 1 -> 15
~~~

In cata(ana(...)), all expansions complete before any reductions
begin. Side effects inside callbacks would make the traces differ.

## Evaluation, termination and errors

All operations are synchronous and strict; no scheduling or I/O
is introduced. Each coalgebra and algebra is called exactly once
per node, in the specified order. Callback references and root
structures are checked eagerly; null layers, child slots and
algebra results are rejected when reached. Exceptions thrown by
user code propagate unchanged, with no partial result returned.

The coalgebra must unfold a **finite tree**. There is no cycle
detection, seed deduplication, time limit, memoization or cancellation.
An endlessly expanding coalgebra never terminates. Shared subtrees
are folded once for each occurrence, not memoized as graphs.

Java cannot enforce purity: user callbacks can mutate values,
perform I/O or diverge. Laws hold for pure, deterministic and
normally terminating functions.

## Iterative traversal and Trampoline

The previous version recursed on the Java call stack. Hercules
uses an **explicit stack of typed frames** instead. Every frame
holds one layer, its next child index and its completed child
results. After the last child, the algebra runs and the result
is appended to the parent frame. This preserves depth-first
order without nested Java method calls.

Both ana and cata reuse the hylo interpreter: ana supplies a
Tree-building algebra; cata projects one Layer from each
existing Tree. hylo never materializes the Tree.

Assuming n nodes, constant-work callbacks and counting each
child slot once:

| Scheme | Time | Memory |
| --- | --- | --- |
| ana | O(n) | O(n) materialized Tree + frames |
| cata | O(n) | O(depth + pending child results), at most O(n) |
| hylo | O(n) | O(depth + pending child results), at most O(n) |
| Layer.map | O(children) | O(children) |

The interpreter still allocates frames, layers and immutable
child-result lists. Large results or arbitrary callback work
can exceed those bounds. Tests verify a **110,000-edge chain**
using ana, cata and hylo, including the entire spine.

Hercules Trampoline is compatible with these pure functions
but not needed to make their traversal stack-safe. Wrapping a
whole traversal in a suspended step merely defers execution;
it does not provide termination or resource limits. The
pretty-printer is iterative too, but a full indented string
takes space proportional to the sum of the node depths.

## Port decisions

**Ported:** Tree/Layer/Morphisms APIs, their Guava ImmutableList
contract, original five-node and numeric walkthrough semantics,
the shape functor laws, and optional tree drawing.

**Adapted:** Java 17 baseline, public bbb.hercules namespace,
JSpecify/Lombok null contracts, iterative stack-safe traversal,
Instancio laws, independent consumer tests, and this tutorial.

**Omitted:** application-specific layouts, filesystem inventory,
workflows, application fixtures and internal package types.

**Deferred:** general graph traversal, memoization, cycle detection,
lazy potentially infinite unfold, explicit step budgets and
non-tree functors.

**Dropped:** a StreamMorphisms.unfold wrapper, because the JDK
already offers Stream.iterate(seed, hasNext, next) and a duplicate
Hercules API adds no value.

Run the tests:

~~~sh
./mvnw -B -ntp -q -Dtest=MorphismsLawsTest,MorphismsPublicApiTest test
~~~

**The original application remains untouched** by this PR. Switching its consumers
to the public Hercules API is a separate migration with its own
review and dependency strategy.
