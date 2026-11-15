package bbb.hercules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic, single-threaded simulation of a homogeneous typed actor
 * protocol. No threads, live actor references, external I/O or scheduler
 * are created. Behavior functions must be pure and states/messages immutable
 * for deterministic, repeatable results.
 *
 * <p>Each actor has private logical state and behavior. The simulator uses
 * an internal mutable FIFO queue strictly as an implementation detail.
 * It processes one envelope at a time, commits one transition, and then
 * appends outgoing messages in effect order. This is not a concurrent
 * runtime. Behavior changes apply only to later envelopes.
 *
 * <p>Duplicate delivery is not removed or hidden. A consumer's state
 * machine decides whether a message has already been processed. No
 * persistence, automatic replay, ordering across external senders,
 * supervision, cancellation or durable delivery is promised.
 *
 * <p>The budgets bound queue length, processed messages and described
 * effects. A budget overflow rejects the entire simulation without a
 * partial result. Runtime exceptions from behavior code propagate unchanged
 * without domain error conversion. The original caller registry is never
 * mutated. Collected LogEvent descriptions may contain sensitive raw fields:
 * use the separate {@link LogInterpreter} before emitting externally.
 */
public final class ActorMachine {

    private ActorMachine() {
    }

    /**
     * An actor's immutable state and current behavior.
     *
     * @param <S> state type
     * @param <M> message protocol
     * @param state current logical state
     * @param behavior state transition function
     */
    public record Cell<S extends @Nullable Object, M>(
            S state, @NonNull ActorBehavior<S, M> behavior) {
    }

    /**
     * Envelope with a typed local destination and a message.
     *
     * @param <M> message protocol
     * @param recipient destination
     * @param message non-null message
     */
    public record Envelope<M>(@NonNull ActorRef<M> recipient, @NonNull M message) {
    }

    /**
     * Explicit simulation budgets. Every number must be positive.
     *
     * @param mailboxCapacity max number of pending envelopes at once
     * @param maxMessages maximum envelope deliveries in one simulation
     * @param maxEffects maximum total descriptions processed across steps
     */
    public record Policy(int mailboxCapacity, int maxMessages, int maxEffects) {
        /**
         * Validate positive queue and work budgets.
         *
         * @param mailboxCapacity max pending queue size
         * @param maxMessages max deliveries in this simulation
         * @param maxEffects max outgoing descriptions
         */
        public Policy {
            if (mailboxCapacity <= 0 || maxMessages <= 0 || maxEffects <= 0) {
                throw new IllegalArgumentException("All actor budgets must be positive");
            }
        }
    }

    /**
     * Immutable snapshot returned after deterministic simulation.
     *
     * @param <S> logical state
     * @param <M> typed protocol
     * @param actors final actors and current behaviors
     * @param pending unprocessed messages in FIFO order
     * @param diagnostics ordered, raw diagnostic descriptions
     * @param processed number of successful message deliveries
     * @param described total outgoing effect descriptions examined
     * @param exhausted true if message budget stopped work while mail remains
     */
    public record Result<S extends @Nullable Object, M>(
            @NonNull Map<ActorRef<M>, Cell<S, M>> actors,
            @NonNull List<Envelope<M>> pending,
            @NonNull List<LogEvent> diagnostics,
            int processed, int described, boolean exhausted) {

        /**
         * Defensively snapshot every result container.
         *
         * @param actors final actor registry
         * @param pending remaining FIFO messages
         * @param diagnostics collected log descriptions
         * @param processed successful message deliveries
         * @param described examined effect descriptions
         * @param exhausted whether delivery stopped at the message budget
         */
        public Result {
            actors = Collections.unmodifiableMap(new LinkedHashMap<>(actors));
            pending = List.copyOf(pending);
            diagnostics = List.copyOf(diagnostics);
            if (processed < 0 || described < 0) {
                throw new IllegalArgumentException("Counters must be nonnegative");
            }
        }

        /**
         * Read state for one actor, without an unchecked cast.
         *
         * @param address the typed local identity
         * @return final actor cell
         */
        public Cell<S, M> actor(@NonNull ActorRef<M> address) {
            return Objects.requireNonNull(actors.get(address), "Unknown actor");
        }
    }

    /**
     * Execute a bounded FIFO simulation from fresh copies of the supplied
     * actor registry and initial messages.
     *
     * <p>Each actor transition executes synchronously to completion.
     * Effects are validated before committing the new state, behavior and
     * resulting envelopes. Failures abort without returning a result.
     * This does not provide transactionality for *user callback effects*
     * such as mutation, external I/O or exceptions.
     *
     * @param <S> logical state
     * @param <M> typed protocol
     * @param registry actor identities, states and pure behaviors
     * @param initial initial ordered envelopes
     * @param policy explicit queue/step/effect budgets
     * @return final immutable snapshot, possibly with unprocessed messages
     */
    public static <S extends @Nullable Object, M> Result<S, M> simulate(
            @NonNull Map<ActorRef<M>, Cell<S, M>> registry,
            @NonNull Iterable<Envelope<M>> initial,
            @NonNull Policy policy) {
        LinkedHashMap<ActorRef<M>, Cell<S, M>> actors = new LinkedHashMap<>();
        registry.forEach((ref, cell) -> actors.put(
                Objects.requireNonNull(ref, "null actor identity"),
                Objects.requireNonNull(cell, "null actor cell")));
        ArrayDeque<Envelope<M>> queue = new ArrayDeque<>();
        Iterator<Envelope<M>> source =
                Objects.requireNonNull(initial.iterator(), "null initial iterator");
        while (source.hasNext()) {
            if (queue.size() == policy.mailboxCapacity()) {
                throw new IllegalStateException("Initial actor mailbox is full");
            }
            Envelope<M> envelope = Objects.requireNonNull(
                    source.next(), "null initial envelope");
            requireAddress(actors, envelope.recipient());
            queue.addLast(envelope);
        }

        ArrayList<LogEvent> diagnostics = new ArrayList<>();
        int processed = 0;
        int described = 0;
        while (!queue.isEmpty() && processed < policy.maxMessages()) {
            Envelope<M> message = queue.removeFirst();
            Cell<S, M> cell = actors.get(message.recipient());
            if (cell == null) {
                throw new IllegalStateException("Unknown actor");
            }
            ActorBehavior.ActorTransition<S, M> next = Objects.requireNonNull(
                    cell.behavior().onMessage(
                            cell.state(), message.message(), message.recipient()),
                    "Actor behavior returned null transition");
            if (next.effects().size() > policy.maxEffects() - described) {
                throw new IllegalStateException("Actor effect budget exhausted");
            }

            ArrayList<Envelope<M>> sends = new ArrayList<>();
            ArrayList<LogEvent> logs = new ArrayList<>();
            for (ActorEffect<M> effect : next.effects()) {
                effect.dispatch(send -> {
                    requireAddress(actors, send.recipient());
                    sends.add(new Envelope<>(send.recipient(), send.message()));
                }, diagnostic -> logs.add(diagnostic.event()));
            }
            if (sends.size() > policy.mailboxCapacity() - queue.size()) {
                throw new IllegalStateException("Actor mailbox is full");
            }
            next.become().ifPresentOrElse(
                    replacement -> actors.put(message.recipient(),
                            new Cell<>(next.state(), replacement)),
                    () -> actors.put(message.recipient(),
                            new Cell<>(next.state(), cell.behavior())));
            queue.addAll(sends);
            diagnostics.addAll(logs);
            described += next.effects().size();
            processed++;
        }
        return new Result<>(actors, List.copyOf(queue), diagnostics,
                processed, described, !queue.isEmpty());
    }

    private static <S extends @Nullable Object, M> void requireAddress(
            Map<ActorRef<M>, Cell<S, M>> registry, ActorRef<M> destination) {
        if (!registry.containsKey(destination)) {
            throw new IllegalArgumentException("Unregistered actor: " + destination.name());
        }
    }
}
