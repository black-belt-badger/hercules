package bbb.hercules;

import java.util.List;
import java.util.Optional;

import lombok.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A pure local actor behavior. Given immutable state, a typed message, and
 * its own address, it computes the next state and effect descriptions.
 *
 * <p>Neither this interface nor Java prevents callback mutation or I/O;
 * referential transparency requires consumers to supply immutable state
 * and side-effect-free implementations. No mailbox or thread exists here.
 *
 * @param <S> immutable logical state type
 * @param <M> typed message protocol
 */
@FunctionalInterface
public interface ActorBehavior<S extends @Nullable Object, M> {

    /**
     * Compute one transition. The simulator rejects a null result.
     *
     * @param state current state for this actor only
     * @param message incoming message of the actor protocol
     * @param self own typed local address
     * @return state, optional changed behavior, and described effects
     */
    ActorTransition<S, M> onMessage(S state, M message, ActorRef<M> self);

    /**
     * Immutable result of one actor behavior evaluation.
     *
     * <p>When {@code become} is empty, the behavior remains unchanged;
     * when present, it replaces the actor's behavior for future messages.
     * The simulator materializes effects in encounter order. Payloads are
     * shallowly immutable and must themselves behave immutably.
     *
     * @param <S> logical state
     * @param <M> message protocol
     * @param state next logical state
     * @param effects outgoing effect descriptions, immutable snapshot
     * @param become optional replacement behavior for future messages
     */
    record ActorTransition<S extends @Nullable Object, M>(
            S state,
            @NonNull List<ActorEffect<M>> effects,
            @NonNull Optional<ActorBehavior<S, M>> become) {

        /**
         * Defensively snapshot the effect descriptions and check elements.
         *
         * @param state next logical state
         * @param effects ordered output descriptions
         * @param become optional next behavior
         */
        public ActorTransition {
            effects = List.copyOf(effects);
        }

        /**
         * Keep the current behavior, with newly computed state and effects.
         *
         * @param <S> state type
         * @param <M> message protocol
         * @param state next state
         * @param effects outgoing descriptions
         * @return transition retaining the previous behavior
         */
        public static <S extends @Nullable Object, M> ActorTransition<S, M> stay(
                S state, @NonNull List<ActorEffect<M>> effects) {
            return new ActorTransition<>(state, effects, Optional.empty());
        }

        /**
         * Change behavior for subsequent messages while returning new state.
         *
         * @param <S> state type
         * @param <M> message protocol
         * @param state next state
         * @param next replacement behavior
         * @param effects ordered outgoing descriptions
         * @return transition with explicit behavior change
         */
        public static <S extends @Nullable Object, M> ActorTransition<S, M> become(
                S state,
                @NonNull ActorBehavior<S, M> next,
                @NonNull List<ActorEffect<M>> effects) {
            return new ActorTransition<>(state, effects, Optional.of(next));
        }
    }
}
