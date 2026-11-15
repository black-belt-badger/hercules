# Pure typed actors: state machines before runtimes

Hercules #18 explores the actor model as **pure immutable state transitions
plus explicit outgoing effect descriptions**, not as a mandatory concurrency
framework. The Java 17 API provides a small deterministic *simulator* for
testing typed local message protocols and communication between actors.

No actor in this design owns a thread, mailbox or OS resource. It is possible
to use ActorBehavior as an ordinary Java function without adopting the
simulator at all.

## Functional algebra

A pure behavior has the conceptual shape:

~~~
step(state, message, self) -> Transition(nextState, effects, become?)
~~~

The main types are:

| Type | Meaning |
| --- | --- |
| ActorRef<M> | Typed, local actor identity in one message protocol |
| ActorBehavior<S,M> | Pure state/message -> transition function |
| ActorTransition<S,M> | New immutable state, ordered effects, optional replacement behavior |
| ActorEffect.Send<M> | Description of a typed message to deliver |
| ActorEffect.Diagnostic<M> | Description of a structured LogEvent |
| ActorMachine.Cell<S,M> | Actor's logical state and current behavior |
| ActorMachine.Envelope<M> | Addressed typed message |
| ActorMachine.Policy | Bounded pending messages, deliveries, described effects |
| ActorMachine.Result<S,M> | Immutable final registry, pending FIFO, diagnostics and counts |

One logical actor processes its messages sequentially. The behavior returns
new state *without modifying the prior snapshot* and can explicitly supply
a different behavior for future messages (become). Effects are just data
until the caller invokes the simulator or another interpreter.

The simulator provides **one common message protocol M and state type S**
across its registry. Java checks that sent messages use that protocol, but
the actor name does **not** statically determine a more specific subtype of
M. This is deliberate: heterogeneous actor registries with distinct typed
mailboxes require existential packaging or erased casts, and a more
sophisticated protocol API should not be invented without a consumer.

## Two communicating actors

The independent-package ActorPublicApiTest runs a simple market-data
example: Fetcher handles Scan messages and produces typed Scanned replies;
Tracker consumes them and advances its immutable offset snapshot.
Both actors share one sealed Message protocol, but have independent states.
The pure producer also describes LogEvent diagnostics:

~~~java
ActorRef<Message> fetcher = new ActorRef<>("fetcher");
ActorRef<Message> tracker = new ActorRef<>("tracker");

ActorBehavior<Snapshot, Message> step = (state, message, self) -> {
    if (message instanceof Scan scan) {
        return ActorBehavior.ActorTransition.stay(state, List.of(
                new ActorEffect.Send<>(scan.replyTo(),
                        new Scanned(scan.offset())),
                new ActorEffect.Diagnostic<>(diagnostic(scan.offset()))));
    }
    return ActorBehavior.ActorTransition.stay(state, List.of());
};

// ActorMachine.simulate(registry, incoming,
//     new ActorMachine.Policy(4, 8, 8))
~~~

Here Scan, Scanned, Snapshot, diagnostic and registry are consumer-owned
protocol and state definitions, not Hercules infrastructure.

No logging or network I/O occurs during this simulation. Only after the
caller obtains its Result can it choose to call LogInterpreter.emit on the
collected LogEvent descriptions. The raw descriptions may contain sensitive
payloads; LogInterpreter redacts them before passing them to an external
sink.

## Mailbox ordering, limits and replay

ActorMachine.simulate copies the caller's registry into local private data.
It first queues initial messages in the supplied order, then repeatedly:

1. Removes one envelope from the front of the FIFO.
2. Calls the addressed actor's behavior once with its **own** state.
3. Validates the returned transition, effect budget, recipients and
   remaining mailbox capacity.
4. Commits the actor's new state and optional replacement behavior.
5. Appends outgoing Send messages to the tail, **in effect order**,
   and collects Diagnostic descriptions, also in encounter order.

Consequently a message already waiting in the queue is processed before
responses produced by later transitions. This is **deterministic global
FIFO for the simulator**; it is not a guarantee about distributed networks,
concurrent producers, multiple mailboxes, or any future actor runtime.

ActorMachine.Policy has three positive explicit budgets:

- mailboxCapacity bounds initial and subsequently pending envelopes
- maxMessages bounds successfully processed envelopes
- maxEffects bounds total outgoing effect descriptions inspected

When the message-step budget is exhausted, simulation returns its immutable
pending FIFO and exhausted=true. It may be resumed by supplying the final
actors and pending envelopes to another simulation.

**Mailbox or effect overflow is a failure**, not a silent drop: the current
simulation throws, without returning a partial Result. A behavior exception,
unknown recipient or malformed transition also propagates as an exception.
The simulator does not retry, restart or supervise a failed actor.

Duplicate messages are **not automatically deduplicated**. The consumer
can carry processed IDs in immutable state and ignore duplicates, as tested
by ActorMachineTest. Re-running from an older snapshot processes a message
again; this is not persistent replay protection or exactly-once delivery.
Deduplication windows, idempotence, persistence and durable acknowledgement
are application/domain concerns.

## Behavior change and state isolation

ActorTransition.stay preserves current behavior for future envelopes.
ActorTransition.become carries a replacement behavior explicitly, without
executing it immediately. A test changes a worker from an active handler
to a dormant handler; a queued message delivered *after* the switch is
ignored by the new behavior.

Actor state is isolated at the level of **state values**: each entry has
its own logical state and transition. Repeated simulations from the same
immutable initial registry produce identical states and diagnostics.
State and message values are only **shallowly immutable**; supplied
mutable lists, Java closures and functions can still leak mutation.
Hercules cannot enforce referential transparency in arbitrary Java lambdas.

A State<S,A> computation is also an explicit pure transition, but it does
not intrinsically model messages to peers or a mailbox. Freer<A> can
describe typed instructions and interpreters, but a general Freer runtime
is unnecessary for this small actor protocol. Plain sealed messages,
ordinary callbacks and the two effect variants are easier to inspect
and debug.

## Runtime boundaries: what this is NOT

The simulator is **synchronous and single-threaded**. It has a bounded
local queue and clear progress semantics but **no concurrent mailbox,
actor-owned thread, parallel scheduling, locking, actor lifecycle,
shutdown, cancellation, restart, supervision, remote messaging or
backpressure protocol**.

A bounded queue in a test simulator is not production backpressure.
It cannot prove concurrent FIFO delivery under races, fairness,
wait-free progress, termination after cancellation, thread-safe
shutdown or crash recovery. Those guarantees require a distinct,
fully specified and tested runtime adapter.

For a future JDK runtime, alternatives include:

- **Direct functions/state loops** when communication is local and simple
- **ArrayDeque in a synchronous driver**, as used here, for deterministic tests
- **ArrayBlockingQueue with threads/executors** for bounded concurrent delivery
- Dedicated actor runtime libraries when supervision and lifecycle matter

If introduced, a runtime should be **in a separate optional integration
module**, not in the lightweight Hercules core. It must specify which
mailbox orders it preserves, what send does at capacity, how stop/drain/
cancel behave, how exceptions are supervised, and what replay or delivery
guarantees—if any—it supplies.

## Scala reference and attribution

The issue cites John A. De Goes, *ZIO 2.0 Released*:
https://degoes.net/articles/zio-2.0

His article describes a **private nano-actor core inside the ZIO 2 effect
runtime**, used to coordinate concurrent access to mutable runtime state
and reduce allocation. He explicitly distinguishes that internal use from
user-facing actor programming, of which he says he is a critic.

Hercules does **not** port that nano-actor implementation. The pure
transition algebra here follows general **Mealy machine** and functional
actor/message-passing ideas. The bounded synchronous simulator, behavior
switching and typed effect descriptions are this prototype's design choices,
not features attributed to, endorsed by, or derived from a particular
De Goes actor API.

## Measured cost against direct functions

After compiling tests, run the optional benchmark:

~~~sh
./mvnw -B -ntp test-compile
java -cp target/classes:target/test-classes \
  bbb.hercules.benchmark.ActorMachineBenchmark 100000
~~~

A single illustrative Java 25 run on Hoopoe for 100,000 local Tick messages:

| Method | Time (ms) | Thread-allocated bytes |
| --- | ---: | ---: |
| Direct state loop | 0.59 | 6,600 |
| Deterministic actor FIFO simulation | 23.62 | 22,904,576 |

The two paths perform the same counter update but not the same
infrastructure work. These are **non-JMH single-run diagnostic values**,
not universal throughput ratios; allocation and timing depend on CPU,
JIT warmup, GC and system load.

The direct function wins decisively on simplicity and overhead for a
simple counter. The simulator earns its complexity only when the
consumer needs reproducible message routing, isolation, behavior changes,
outgoing effects and several communicating state machines.

## Implement / defer / split

**Implement now:** the small opt-in pure actor behavior/transition
types, typed local envelopes and effects, deterministic bounded FIFO
simulator, tests with two actors, duplicate/replay handling, state
isolation, behavior switching and explicit diagnostic interpretation.

**Defer:** heterogeneous per-actor protocol registries, supervision,
durable actor state, replay journals, asynchronous effect execution,
distributed delivery and an actor DSL. Java's current erasure means a
universal heterogeneously typed actor runtime would require substantially
more type-system and API design.

**Split from Hercules core if justified:** any real concurrent mailbox
runtime, in a separate integration artifact, with independent stress,
ordering, backpressure, cancellation, lifecycle and race tests.

**Drop as a default:** using actors where direct functions, State or
a simple queue suffice; mandatory Vert.x, Akka, ZIO, Reactor or broker
dependencies; claims that the simulator provides concurrency or exactly-once
delivery.

Related issues: #1 (logging effects), #3 (State/Reader/Writer),
#4 (Trampoline), #12 (Freer).
