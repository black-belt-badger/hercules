package bbb.hercules;

import java.util.Objects;
import java.util.Optional;

import lombok.NonNull;

/**
 * A small, typed, domain-neutral instruction signature for {@link Freer}.
 *
 * <p>Each instruction fixes its own result type: reading returns an
 * {@link Optional}, while writing state and describing a diagnostic return
 * {@link State.Unit}. This is a limited encoding of GADT-like instructions
 * using ordinary Java sealed types; it is not a general higher-kinded
 * functor signature.
 *
 * <p>Creating instructions performs no I/O or mutation. An interpreter
 * supplies the behavior through the explicitly passed {@link Handler}.
 *
 * @param <A> result type of this instruction
 */
public sealed interface Instruction<A>
        permits Instruction.Query, Instruction.Emit, Instruction.Update {

    /**
     * Interpret this one instruction against a caller-supplied handler.
     *
     * <p>The handler is an explicit execution boundary. Its methods may
     * perform effects; creating the instruction itself never does so.
     *
     * @param handler selected implementation of the three operations
     * @return typed instruction result
     */
    A execute(@NonNull Handler handler);

    /** An explicitly supplied interpretation for the instruction language. */
    interface Handler {
        /**
         * Read the value associated with a key, if any.
         *
         * @param key name of the queried value
         * @return present or empty typed query result
         */
        Optional<String> query(String key);

        /**
         * Consume a diagnostic description; interpretation decides on I/O.
         *
         * @param event immutable diagnostic description
         */
        void emit(LogEvent event);

        /**
         * Replace a keyed value in interpreter-owned state.
         *
         * @param key name to update
         * @param value replacement string value
         */
        void update(String key, String value);
    }

    /**
     * Query keyed interpreter state.
     *
     * @param key name of the value to query
     */
    record Query(@NonNull String key) implements Instruction<Optional<String>> {
        @Override
        public Optional<String> execute(@NonNull Handler handler) {
            return Objects.requireNonNull(handler.query(key),
                    "query interpreter returned null Optional");
        }
    }

    /**
     * Describe a structured diagnostic to be consumed at interpretation.
     *
     * @param event immutable logging event
     */
    record Emit(@NonNull LogEvent event) implements Instruction<State.Unit> {
        @Override
        public State.Unit execute(@NonNull Handler handler) {
            handler.emit(event);
            return State.Unit.INSTANCE;
        }
    }

    /**
     * Update a named value in interpreter-owned state.
     *
     * @param key name to update
     * @param value replacement value
     */
    record Update(@NonNull String key, @NonNull String value)
            implements Instruction<State.Unit> {
        @Override
        public State.Unit execute(@NonNull Handler handler) {
            handler.update(key, value);
            return State.Unit.INSTANCE;
        }
    }
}
