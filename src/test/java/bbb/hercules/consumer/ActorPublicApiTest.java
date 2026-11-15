package bbb.hercules.consumer;

import bbb.hercules.ActorBehavior;
import bbb.hercules.ActorEffect;
import bbb.hercules.ActorMachine;
import bbb.hercules.ActorRef;
import bbb.hercules.LogEvent;
import bbb.hercules.LogField;
import bbb.hercules.LogInterpreter;
import bbb.hercules.LogPolicy;
import bbb.hercules.LogValue;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Pure two-actor communication using the public Hercules actor core. */
class ActorPublicApiTest {

    sealed interface Message permits Scan, Scanned { }
    record Scan(int offset, ActorRef<Message> replyTo) implements Message { }
    record Scanned(int offset) implements Message { }
    record Snapshot(List<Integer> received) {
        Snapshot { received = List.copyOf(received); }
    }

    private static final ActorRef<Message> FETCHER = new ActorRef<>("fetcher");
    private static final ActorRef<Message> TRACKER = new ActorRef<>("tracker");
    private static final Snapshot EMPTY = new Snapshot(List.of());

    private static LogEvent event(int offset) {
        return new LogEvent(LogEvent.Level.INFO, "scan.completed",
                Instant.parse("2026-11-15T10:00:00Z"), "Fetcher",
                Optional.empty(), Optional.empty(), Map.of(
                "offset", new LogField(LogField.Visibility.PUBLIC,
                        new LogValue.Count(offset))));
    }

    @Test
    void typedActorsExchangeMessagesWithoutExternalEffects() {
        ActorBehavior<Snapshot, Message> fetcher = (state, message, self) -> {
            if (message instanceof Scan scan) {
                return ActorBehavior.ActorTransition.stay(state, List.of(
                        new ActorEffect.Send<>(scan.replyTo(), new Scanned(scan.offset())),
                        new ActorEffect.Diagnostic<>(event(scan.offset()))));
            }
            return ActorBehavior.ActorTransition.stay(state, List.of());
        };
        ActorBehavior<Snapshot, Message> tracker = (state, message, self) -> {
            if (message instanceof Scanned response) {
                var updated = new java.util.ArrayList<>(state.received());
                updated.add(response.offset());
                return ActorBehavior.ActorTransition.stay(new Snapshot(updated), List.of());
            }
            return ActorBehavior.ActorTransition.stay(state, List.of());
        };
        Map<ActorRef<Message>, ActorMachine.Cell<Snapshot, Message>> registry = Map.of(
                FETCHER, new ActorMachine.Cell<>(EMPTY, fetcher),
                TRACKER, new ActorMachine.Cell<>(EMPTY, tracker));
        var initial = List.of(
                new ActorMachine.Envelope<Message>(FETCHER, new Scan(10, TRACKER)),
                new ActorMachine.Envelope<Message>(FETCHER, new Scan(11, TRACKER)));
        var result = ActorMachine.simulate(registry, initial,
                new ActorMachine.Policy(4, 8, 8));

        assertEquals(List.of(10, 11), result.actor(TRACKER).state().received());
        assertEquals(List.of(), result.actor(FETCHER).state().received());
        assertEquals(List.of(), registry.get(TRACKER).state().received());
        assertEquals(4, result.processed());
        assertEquals(4, result.described());

        AtomicInteger emitted = new AtomicInteger();
        assertEquals(0, emitted.get(), "the actor simulator never invokes a log backend");
        var report = LogInterpreter.emit(result.diagnostics(), safe -> emitted.incrementAndGet(),
                LogPolicy.strict(4));
        assertEquals(2, emitted.get());
        assertEquals(2, report.accepted());
        assertEquals(result.actor(TRACKER).state(),
                ActorMachine.simulate(registry, initial,
                        new ActorMachine.Policy(4, 8, 8)).actor(TRACKER).state());
    }
}
