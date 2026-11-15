package bbb.hercules;

import lombok.NonNull;

/**
 * A typed, pure description of what an actor wants interpreted after a
 * successful state transition. Neither variant sends or logs by itself.
 *
 * @param <M> protocol of outgoing actor messages
 */
public sealed interface ActorEffect<M>
        permits ActorEffect.Send, ActorEffect.Diagnostic {

    /**
     * Type-preserving case analysis, without unchecked casts.
     *
     * @param onSend process the typed outgoing message
     * @param onDiagnostic process the diagnostic description
     */
    void dispatch(java.util.function.Consumer<? super Send<M>> onSend,
                  java.util.function.Consumer<? super Diagnostic<M>> onDiagnostic);

    /**
     * Request delivery to another local actor.
     *
     * @param <M> message protocol
     * @param recipient target in the local simulation
     * @param message immutable typed message value
     */
    record Send<M>(@NonNull ActorRef<M> recipient, @NonNull M message)
            implements ActorEffect<M> {
        @Override
        public void dispatch(
                @NonNull java.util.function.Consumer<? super Send<M>> onSend,
                @NonNull java.util.function.Consumer<? super Diagnostic<M>> onDiagnostic) {
            onSend.accept(this);
        }
    }

    /**
     * Describe a diagnostic; it is collected as data, not emitted to a backend.
     *
     * @param <M> message protocol
     * @param event immutable diagnostic description
     */
    record Diagnostic<M>(@NonNull LogEvent event) implements ActorEffect<M> {
        @Override
        public void dispatch(
                @NonNull java.util.function.Consumer<? super Send<M>> onSend,
                @NonNull java.util.function.Consumer<? super Diagnostic<M>> onDiagnostic) {
            onDiagnostic.accept(this);
        }
    }
}
