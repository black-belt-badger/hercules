# Free and Freer: typed programs and explicit interpretation

Hercules #12 compares **classic Free** and **typed Freer/operational**
program representations. Programs are constructed as immutable descriptions,
not as hidden side effects. An explicitly supplied interpreter chooses
whether to collect data or interact with a backend.

## Executable prototypes

| Prototype | Encoding | Interpreters |
| --- | --- | --- |
| Haskell 9.10 | Classic Free f a, a Functor instruction layer | Pure state and ordered events |
| Haskell 9.10 | GADT Instruction a, Freer a | Pure state and optional stdout I/O |
| Java 17+ | Sealed Instruction<A>, Freer<A> | Pure simulate and explicit redacted emit |

Run the Haskell reference with GHC's base and containers packages:

~~~sh
runghc experiments/haskell/FreeFreer.hs
# PASS Haskell classic Free and GADT/Freer reference laws.

runghc experiments/haskell/FreeFreer.hs --emit
# PASS ... then [explicit effect] instrument.checked
~~~

The source includes both encodings for the **same query/update/emit
program**. It checks left/right identity, associativity, ordered
events, pure interpreter parity and repeatable evaluation.

## Why classic Free and Freer differ

In Haskell, a classic Free construction uses a higher-kinded
Functor f to describe one instruction layer plus continuations:

~~~haskell
data Free f a = PureF a | Roll (f (Free f a))
~~~

The instruction layer supplies Functor mapping. An alternative
GADT-based Freer representation instead fixes an exact result
type on every instruction, without requiring Instruction to be
a Functor:

~~~haskell
data Instruction a where
  Query  :: String -> Instruction (Maybe String)
  Emit   :: String -> Instruction ()
  Update :: String -> String -> Instruction ()

data Freer a where
  PureR :: a -> Freer a
  BindR :: Instruction x -> (x -> Freer a) -> Freer a
~~~

The simple Haskell interpreters are recursive, educational
references; they do not establish arbitrary-depth stack safety
for those particular implementations.

Java has no native HKTs or Haskell-style GADT matching. Pretending
to implement a universal Free<F,A> by wrapping Object values
would introduce weak type contracts and unchecked casts.
Hercules uses a deliberately **concrete, result-indexed**
instruction signature instead.

## Java API and two interpretation boundaries

A typed query returns Optional<String>. An update or emit returns
the existing non-null State.Unit marker:

~~~java
Instruction<Optional<String>> query = new Instruction.Query("symbol");
Instruction<State.Unit> update = new Instruction.Update("last", "BTCUSD");
Instruction<State.Unit> emit = new Instruction.Emit(logEvent);
~~~

Each instruction dispatches through a strongly typed Handler,
without unchecked casts. The handler is **supplied only at
interpretation**.

~~~java
Freer<Optional<String>> program =
        Freer.lift(new Instruction.Query("symbol"))
            .flatMap(found -> Freer.lift(
                    new Instruction.Update("last", found.orElse("missing")))
                .flatMap(ignored -> Freer.lift(new Instruction.Emit(logEvent))
                    .flatMap(done -> Freer.lift(new Instruction.Query("last")))));

FreerInterpreter.Simulation<Optional<String>> test =
        FreerInterpreter.simulate(program, Map.of("symbol", "BTCUSD"));

FreerInterpreter.Emission<Optional<String>> live =
        FreerInterpreter.emit(program, Map.of("symbol", "BTCUSD"),
                safeEvent -> selectedBackend.accept(safeEvent));
~~~

The logging event and backend are caller-supplied values.
Building the program invokes no backend, network, clock or logging I/O.

Simulation starts from a defensive copy of the supplied state map,
runs the instructions with **local internal state**, and returns a
new immutable final state, final value and collected event
descriptions. This is observationally pure when instructions
and consumers are deterministic.

Emission uses the same query/update semantics, but calls the
supplied sink synchronously for each Emit instruction, passing
**redacted LogEvent copies**. It returns final value/state plus
the count of normally returned sink calls. It does NOT promise
durable delivery or retain any event history.

**Security boundary:** simulation exposes original raw event
descriptions to its own caller for inspection; these may contain
secrets. The emission interpreter sanitizes via LogEvent.redacted
before calling any external backend. Correct field classification,
retention, credentials and source metadata remain caller concerns.

The caller-supplied input map is not mutated. Each evaluation
starts independently and is not memoized.

## Inspectability and dynamic branches

Public Freer.Pure and Freer.Suspend expose their value or
typed instruction. Freer.shape reports PURE, SUSPENDED or BIND
without execution. Bind nodes are private and store typed
dependent continuations.

A dynamically branching program is **not a fully static list
of all future instructions**: a query result can decide which
instructions are constructed next. Only the current syntax is
inspectable without evaluating preceding effects. For a closed
fixed sequence, a plain List of ADT instructions is simpler.

## Laws and stack safety

For pure deterministic interpreters, observational equality
compares final value, state and ordered events, not the syntax
tree's object identity. The Java tests use Instancio-generated
values to check:

~~~text
Functor identity:        m.map(identity) ~= m
Functor composition:     m.map(f).map(g) ~= m.map(f.andThen(g))
Monad left identity:     pure(a).flatMap(f) ~= f(a)
Monad right identity:    m.flatMap(Freer::pure) ~= m
Monad associativity:     m.flatMap(f).flatMap(g)
                      ~= m.flatMap(a -> f(a).flatMap(g))
~~~

Other tests cover typed query results, ordered state mutation,
sensitive field redaction, pure-vs-emitting parity, exception
propagation, nullness, lazy construction and independent runs.

**One million left-associated binds** and **120,000 typed
state-update instructions** execute without Java stack overflow.
A private typed Bind<S,A> node reassociates pending binds one
step at a time, exactly as Hercules Trampoline does.
The interpreter is iterative and uses **no unchecked casts**.

This does not guarantee termination, purity of arbitrary user
handlers, or stack safety for a user's own recursive callbacks.
Nothing here schedules work, retries, cancels requests or
turns external failures into domain-level errors.

## Reproducible benchmark

~~~sh
./mvnw -B -ntp test-compile
java -cp target/classes:target/test-classes \
  bbb.hercules.benchmark.FreerBenchmark 100000
~~~

One illustrative Java 25 run on Hoopoe, 100,000 updates:

| Phase | Time (ms) | Allocated bytes |
| --- | ---: | ---: |
| Build direct immutable ADT list | 11.24 | 8,406,720 |
| Build Freer description | 9.45 | 8,800,048 |
| Interpret direct ADT | 7.53 | 328 |
| Interpret Freer | 27.51 | 28,000,816 |

Both produce the same final counter. These are single-run,
JIT/load/GC-dependent observations, **not JMH benchmarks**.
The direct ADT stores fixed instructions ahead of evaluation,
while Freer constructs dependent operations dynamically; the
workloads are analogous, not identical. Freer is more costly
to interpret because it allocates and dispatches continuations.

For a known fixed sequence or an ordinary state machine, prefer
the simpler direct ADT interpreter. Freer is useful when later
instructions genuinely depend on earlier typed results and
multiple interpreters justify the abstraction.

## Implement / defer / drop

**Implement:** a focused Java 17 typed Instruction language,
a stack-safe Freer description, pure simulation and caller-owned
redacted emission, plus a standalone Haskell classic Free/GADT
reference. All are opt-in and have no new runtime dependencies.

**Defer:** generic higher-kinded Free<F,A>, open effect signatures,
FreeT/FreerT, general effect-handler frameworks, async execution,
resource handling and a full Haskell distributable package.
Revisit only when a concrete consumer justifies the complexity.

**Drop as a universal default:** rewriting ordinary workflows as
Freer programs, global side-effecting handlers and unchecked
Object-based GADT emulations. Java and Haskell need not have
identical syntax to satisfy the same semantic contracts.

Related: #1 interpreted logging, #3 Reader/Writer/State,
#4 Trampoline, #15 transformers and #18 typed actors.
