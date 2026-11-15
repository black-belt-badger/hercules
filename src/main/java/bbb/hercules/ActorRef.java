package bbb.hercules;

import lombok.NonNull;

/**
 * A typed local address. The message type parameter prevents accidentally
 * using references from distinct message protocols in the same simulation.
 *
 * <p>This is not a remote endpoint, a running mailbox, or a stable
 * cross-process identity. Different message types have no runtime class
 * token; erasure limits protocol isolation to compile-time usages.
 *
 * @param <M> the message protocol
 * @param name stable nonblank local actor identifier
 */
public record ActorRef<M>(@NonNull String name) {
    /**
     * Reject empty and whitespace-only identifiers.
     *
     * @param name nonblank identity
     */
    public ActorRef {
        if (name.isBlank()) {
            throw new IllegalArgumentException("Actor name must not be blank");
        }
    }
}
