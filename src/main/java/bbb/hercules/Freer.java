package bbb.hercules;

import java.util.Objects;
import java.util.function.Function;

import lombok.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A typed, inspectable description of dependent instruction computations.
 *
 * <p>This is an operational/Freer encoding: a suspended node pairs an
 * {@link Instruction} returning a particular result type with a typed
 * continuation. The private bind node preserves heterogeneous intermediate
 * types and reassociates nested binds without recursive Java evaluation.
 *
 * <p>Constructors, {@link #map(Function)} and {@link #flatMap(Function)}
 * do not execute instructions, mappers or continuations. An explicit
 * {@link #interpret(Instruction.Handler)} call evaluates the program on the
 * calling thread. The interpreter decides whether to collect data or
 * perform external effects.
 *
 * <p>{@link Pure} and {@link Suspend} are public inspectable nodes;
 * {@code Bind} is private. Dynamic branches cannot be fully enumerated
 * before supplying instruction results. This is not a general HKT-based
 * {@code Free<F,A>}: Java has no direct encoding of higher-kinded functor
 * parameters or Haskell GADT result indexing.
 *
 * <p>Explicitly nullable completed payloads are allowed via JSpecify.
 * Structural instructions, continuations and interpreter references
 * are required non-null. A null continuation result is rejected at
 * evaluation time; exceptions from handlers propagate unchanged.
 *
 * @param <A> final result type
 */
public abstract sealed class Freer<A extends @Nullable Object> {

    private Freer() {
    }

    /** The outermost syntax form, obtainable without evaluating effects. */
    public enum Shape {
        /** A completed value. */
        PURE,
        /** A typed suspended instruction. */
        SUSPENDED,
        /** A deferred dependent continuation. */
        BIND
    }

    /**
     * Identify the outermost syntax form without interpretation.
     *
     * @return PURE, SUSPENDED or BIND
     */
    public abstract Shape shape();

    /**
     * Execute this program through the explicitly supplied handler.
     *
     * <p>Execution is iterative; deep left-associated binds are
     * reassociated without recursively invoking another interpreter.
     * This method does not schedule work, memoize results, or catch user
     * exceptions. An infinite program never terminates.
     *
     * @param handler explicit interpretation of each instruction
     * @return the typed final value, possibly explicitly nullable
     */
    public final A interpret(@NonNull Instruction.Handler handler) {
        Freer<A> current = this;
        while (!current.finished()) {
            current = current.advance(handler);
        }
        return current.completed();
    }

    /**
     * Lazily transform the computed value without interpreting instructions.
     *
     * @param <B> mapped value type
     * @param mapper required mapping function
     * @return a new program with the mapped result
     */
    public final <B extends @Nullable Object> Freer<B> map(
            @NonNull Function<? super A, ? extends B> mapper) {
        return flatMap(value -> pure(mapper.apply(value)));
    }

    /**
     * Lazily bind a computation chosen from the prior result.
     *
     * @param <B> dependent value type
     * @param next required typed continuation
     * @return a composed, immutable description
     */
    public final <B extends @Nullable Object> Freer<B> flatMap(
            @NonNull Function<? super A, ? extends Freer<B>> next) {
        return new Bind<>(this, next);
    }

    /**
     * Create a completed value, including an explicitly nullable payload.
     *
     * @param <A> computed value type
     * @param value completed payload
     * @return a pure program
     */
    public static <A extends @Nullable Object> Freer<A> pure(A value) {
        return new Pure<>(value);
    }

    /**
     * Suspend one typed instruction without executing it.
     *
     * @param <A> typed instruction result
     * @param operation required instruction description
     * @return a suspended program
     */
    public static <A extends @Nullable Object> Freer<A> lift(
            @NonNull Instruction<A> operation) {
        return new Suspend<>(operation);
    }

    abstract boolean finished();

    abstract A completed();

    abstract Freer<A> advance(Instruction.Handler handler);

    abstract <B extends @Nullable Object> Freer<B> continueWith(
            Function<? super A, ? extends Freer<B>> next,
            Instruction.Handler handler);

    /** Inspectable completed program value. */
    public static final class Pure<A extends @Nullable Object> extends Freer<A> {
        private final A value;

        /**
         * Construct an immutable pure value.
         *
         * @param value pure payload
         */
        public Pure(A value) {
            this.value = value;
        }

        /**
         * Return the completed payload without interpreting effects.
         *
         * @return the pure value
         */
        public A value() {
            return value;
        }

        @Override
        public Shape shape() {
            return Shape.PURE;
        }

        @Override
        boolean finished() {
            return true;
        }

        @Override
        A completed() {
            return value;
        }

        @Override
        Freer<A> advance(Instruction.Handler handler) {
            throw new IllegalStateException("Cannot advance a pure program");
        }

        @Override
        <B extends @Nullable Object> Freer<B> continueWith(
                Function<? super A, ? extends Freer<B>> next,
                Instruction.Handler handler) {
            return Objects.requireNonNull(next.apply(value),
                    "flatMap returned null Freer");
        }
    }

    /** Inspectable one-instruction suspension. */
    public static final class Suspend<A extends @Nullable Object> extends Freer<A> {
        private final Instruction<A> operation;

        /**
         * Construct a typed suspended instruction.
         *
         * @param operation instruction data, not its result
         */
        public Suspend(@NonNull Instruction<A> operation) {
            this.operation = operation;
        }

        /**
         * Return the instruction description without executing it.
         *
         * @return the suspended instruction
         */
        public Instruction<A> operation() {
            return operation;
        }

        @Override
        public Shape shape() {
            return Shape.SUSPENDED;
        }

        @Override
        boolean finished() {
            return false;
        }

        @Override
        A completed() {
            throw new IllegalStateException("Suspended instruction is not completed");
        }

        @Override
        Freer<A> advance(Instruction.Handler handler) {
            return pure(operation.execute(handler));
        }

        @Override
        <B extends @Nullable Object> Freer<B> continueWith(
                Function<? super A, ? extends Freer<B>> next,
                Instruction.Handler handler) {
            return new Bind<>(advance(handler), next);
        }
    }

    /**
     * Captures an existential intermediate type and reassociates one bind
     * at a time, avoiding unchecked casts and a recursive visitor stack.
     */
    private static final class Bind<S extends @Nullable Object, A extends @Nullable Object>
            extends Freer<A> {
        private final Freer<S> source;
        private final Function<? super S, ? extends Freer<A>> binder;

        private Bind(Freer<S> source,
                     Function<? super S, ? extends Freer<A>> binder) {
            this.source = source;
            this.binder = binder;
        }

        @Override
        public Shape shape() {
            return Shape.BIND;
        }

        @Override
        boolean finished() {
            return false;
        }

        @Override
        A completed() {
            throw new IllegalStateException("Bound computation is not completed");
        }

        @Override
        Freer<A> advance(Instruction.Handler handler) {
            return source.continueWith(binder, handler);
        }

        @Override
        <B extends @Nullable Object> Freer<B> continueWith(
                Function<? super A, ? extends Freer<B>> next,
                Instruction.Handler handler) {
            return new Bind<>(source, value -> Objects.requireNonNull(
                    binder.apply(value), "flatMap returned null Freer").flatMap(next));
        }
    }
}
