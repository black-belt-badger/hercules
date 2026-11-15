package bbb.hercules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import lombok.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Two explicit interpreters for a small typed {@link Instruction} language.
 *
 * <p>{@link #simulate(Freer, Map)} uses only local in-memory state and
 * collects immutable event descriptions, performing no external I/O.
 * {@link #emit(Freer, Map, Consumer)} delegates only redacted diagnostic
 * events to a caller-chosen backend, with unchanged state semantics.
 *
 * <p>Each interpretation starts from a defensive state snapshot, without
 * modifying the caller's input map. Internal mutable collections are local
 * to this invocation and never escape without a defensive copy. Operations
 * execute in instruction order and no threads or schedulers are introduced.
 * The explicit I/O sink may throw: that exception propagates without
 * conversion to domain data, and no result is returned.
 *
 * <p>These two interpreters demonstrate semantics; they are not a database
 * transaction, logging delivery protocol or generalized effect runtime.
 */
public final class FreerInterpreter {

    private FreerInterpreter() {
    }

    /**
     * Pure observation of a program, with collected diagnostic descriptions.
     *
     * @param <A> program result type
     * @param program typed program to evaluate
     * @param initial initial immutable mapping of string keys to values
     * @return final value, state and collected event descriptions
     */
    public static <A extends @Nullable Object> Simulation<A> simulate(
            @NonNull Freer<A> program, @NonNull Map<String, String> initial) {
        Execution execution = new Execution(initial, null);
        A value = program.interpret(execution);
        return new Simulation<>(value, execution.snapshot(), execution.collected());
    }

    /**
     * Emit redacted events to a chosen external consumer at interpretation.
     *
     * <p>Only {@link LogEvent#redacted()} copies reach the sink; originals
     * can still be inspected in the pure producer's program data. This
     * method does not promise durable delivery, retry or compensation.
     *
     * @param <A> program result type
     * @param program typed program
     * @param initial initial input snapshot
     * @param sink explicit synchronous backend receiving redacted events
     * @return final value, immutable state and successfully returned sink call count
     */
    public static <A extends @Nullable Object> Emission<A> emit(
            @NonNull Freer<A> program, @NonNull Map<String, String> initial,
            @NonNull Consumer<? super LogEvent> sink) {
        Execution execution = new Execution(initial, sink);
        A value = program.interpret(execution);
        return new Emission<>(value, execution.snapshot(), execution.emitted);
    }

    /**
     * Purely simulated result with defensive snapshots.
     *
     * @param <A> computation result type
     * @param value final value
     * @param state final immutable state
     * @param events immutable event descriptions, in encounter order
     */
    public record Simulation<A extends @Nullable Object>(
            A value, @NonNull Map<String, String> state,
            @NonNull List<LogEvent> events) {
        /**
         * Snapshot the caller-supplied result containers.
         *
         * @param value computed value
         * @param state final immutable state
         * @param events ordered immutable diagnostic descriptions
         */
        public Simulation {
            state = Map.copyOf(state);
            events = List.copyOf(events);
        }
    }

    /**
     * Effectful interpreter's final result; emission counts are not
     * durable acknowledgement.
     *
     * @param <A> computation result type
     * @param value final value
     * @param state final immutable state
     * @param emitted sink calls returning normally
     */
    public record Emission<A extends @Nullable Object>(
            A value, @NonNull Map<String, String> state, int emitted) {
        /**
         * Snapshot state and validate the emission counter.
         *
         * @param value computed value
         * @param state final immutable state
         * @param emitted successful backend invocations
         */
        public Emission {
            state = Map.copyOf(state);
            if (emitted < 0) {
                throw new IllegalArgumentException("emitted must not be negative");
            }
        }
    }

    /** Local mutable state is encapsulated inside one explicit interpreter call. */
    private static final class Execution implements Instruction.Handler {
        private final LinkedHashMap<String, String> store;
        private final ArrayList<LogEvent> descriptions = new ArrayList<>();
        private final @Nullable Consumer<? super LogEvent> sink;
        private int emitted;

        private Execution(Map<String, String> initial,
                          @Nullable Consumer<? super LogEvent> sink) {
            this.store = new LinkedHashMap<>();
            initial.forEach((key, value) -> this.store.put(
                    Objects.requireNonNull(key, "null state key"),
                    Objects.requireNonNull(value, "null state value")));
            this.sink = sink;
        }

        @Override
        public java.util.Optional<String> query(String key) {
            return java.util.Optional.ofNullable(store.get(key));
        }

        @Override
        public void update(String key, String value) {
            store.put(key, value);
        }

        @Override
        public void emit(LogEvent event) {
            if (sink == null) {
                descriptions.add(event);
            } else {
                sink.accept(event.redacted());
                emitted++;
            }
        }

        private Map<String, String> snapshot() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(store));
        }

        private List<LogEvent> collected() {
            return List.copyOf(descriptions);
        }
    }
}
