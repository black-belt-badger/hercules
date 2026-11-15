package bbb.hercules;

import org.instancio.junit.Given;
import org.instancio.junit.InstancioExtension;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(InstancioExtension.class)
class ActorMachineTest {

    private sealed interface Message permits Request, Acknowledged, Freeze { }

    private record Request(int sequence, ActorRef<Message> replyTo) implements Message { }
    private record Acknowledged(int sequence) implements Message { }
    private record Freeze() implements Message { }

    private record Memory(List<Integer> seen, int duplicates, List<Integer> confirmed) {
        private Memory {
            seen = List.copyOf(seen);
            confirmed = List.copyOf(confirmed);
        }

        Memory received(int seq) {
            var next = new ArrayList<>(seen);
            next.add(seq);
            return new Memory(next, duplicates, confirmed);
        }
        Memory duplicate() { return new Memory(seen, duplicates + 1, confirmed); }
        Memory confirmed(int seq) {
            var next = new ArrayList<>(confirmed);
            next.add(seq);
            return new Memory(seen, duplicates, next);
        }
    }

    private static final ActorRef<Message> PRODUCER = new ActorRef<>("producer");
    private static final ActorRef<Message> WORKER = new ActorRef<>("worker");
    private static final Memory EMPTY = new Memory(List.of(), 0, List.of());

    private static LogEvent diagnostic(int sequence) {
        return new LogEvent(LogEvent.Level.INFO, "work.accepted",
                Instant.parse("2026-11-15T10:00:00Z"), "ActorTest",
                Optional.empty(), Optional.empty(), Map.of(
                "seq", new LogField(LogField.Visibility.PUBLIC,
                        new LogValue.Count(sequence))));
    }

    private static ActorBehavior<Memory, Message> producer() {
        return (state, message, self) -> {
            if (message instanceof Acknowledged ack) {
                return ActorBehavior.ActorTransition.stay(
                        state.confirmed(ack.sequence()), List.of());
            }
            return ActorBehavior.ActorTransition.stay(state, List.of());
        };
    }

    private static ActorBehavior<Memory, Message> worker() {
        return (state, message, self) -> {
            if (message instanceof Request request) {
                if (state.seen().contains(request.sequence())) {
                    return ActorBehavior.ActorTransition.stay(state.duplicate(), List.of());
                }
                return ActorBehavior.ActorTransition.stay(
                        state.received(request.sequence()), List.of(
                                new ActorEffect.Send<>(
                                        request.replyTo(),
                                        new Acknowledged(request.sequence())),
                                new ActorEffect.Diagnostic<>(diagnostic(request.sequence()))));
            }
            return ActorBehavior.ActorTransition.stay(state, List.of());
        };
    }

    private static Map<ActorRef<Message>, ActorMachine.Cell<Memory, Message>> registry() {
        return Map.of(
                PRODUCER, new ActorMachine.Cell<>(EMPTY, producer()),
                WORKER, new ActorMachine.Cell<>(EMPTY, worker()));
    }

    private static ActorMachine.Envelope<Message> toWorker(int sequence) {
        return new ActorMachine.Envelope<>(WORKER, new Request(sequence, PRODUCER));
    }

    @Test
    void twoActorsExchangeTypedMessagesWithDeterministicFifoAndIsolation() {
        var initial = List.of(toWorker(1), toWorker(1), toWorker(2));
        var policy = new ActorMachine.Policy(5, 20, 20);
        var first = ActorMachine.simulate(registry(), initial, policy);
        var second = ActorMachine.simulate(registry(), initial, policy);

        assertEquals(new Memory(List.of(1, 2), 1, List.of()),
                first.actor(WORKER).state());
        assertEquals(new Memory(List.of(), 0, List.of(1, 2)),
                first.actor(PRODUCER).state());
        assertEquals(List.of("work.accepted", "work.accepted"),
                first.diagnostics().stream().map(LogEvent::name).toList());
        assertEquals(List.of(1L, 2L), first.diagnostics().stream()
                .map(log -> ((LogValue.Count) log.fields().get("seq").value()).value())
                .toList());
        assertEquals(5, first.processed());
        assertEquals(4, first.described());
        assertEquals(List.of(), first.pending());
        assertFalse(first.exhausted());
        assertEquals(first.processed(), second.processed());
        assertEquals(first.actor(WORKER).state(), second.actor(WORKER).state());
        assertEquals(first.actor(PRODUCER).state(), second.actor(PRODUCER).state());
        assertEquals(first.diagnostics(), second.diagnostics());
        assertEquals(EMPTY, registry().get(WORKER).state());
    }

    @RepeatedTest(64)
    void repeatedIndependentMessagesPreserveOrderAndLawfulStateIsolation(
            @Given int seed) {
        int count = 1 + Math.floorMod(seed, 80);
        List<ActorMachine.Envelope<Message>> incoming = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            incoming.add(toWorker(i));
        }
        var result = ActorMachine.simulate(registry(), incoming,
                new ActorMachine.Policy(count + 1, 2 * count + 1, 3 * count + 1));

        List<Integer> oracle = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            oracle.add(i);
        }
        assertEquals(oracle, result.actor(WORKER).state().seen());
        assertEquals(oracle, result.actor(PRODUCER).state().confirmed());
        assertEquals(count * 2, result.processed());
        assertEquals(count * 2, result.described());
        assertFalse(result.exhausted());
    }

    @Test
    void replayAndDuplicateSemanticsAreChosenByStateNotSimulator() {
        var first = ActorMachine.simulate(registry(), List.of(toWorker(7)),
                new ActorMachine.Policy(2, 4, 4));
        var after = ActorMachine.simulate(first.actors(), List.of(toWorker(7)),
                new ActorMachine.Policy(2, 4, 4));
        assertEquals(List.of(7), after.actor(WORKER).state().seen());
        assertEquals(1, after.actor(WORKER).state().duplicates());
        assertEquals(List.of(7), after.actor(PRODUCER).state().confirmed());
        assertEquals(List.of(), after.diagnostics());
        assertEquals(1, after.processed());
        assertEquals(0, after.described());

        // Starting from an older snapshot reprocesses the message.
        var reloaded = ActorMachine.simulate(registry(), List.of(toWorker(7)),
                new ActorMachine.Policy(2, 4, 4));
        assertEquals(first.diagnostics(), reloaded.diagnostics());
    }

    @Test
    void behaviorChangeAffectsOnlySubsequentMessages() {
        ActorBehavior<Memory, Message> inactive =
                (state, message, self) -> ActorBehavior.ActorTransition.stay(state, List.of());
        ActorBehavior<Memory, Message> switchable = (state, message, self) -> {
            if (message instanceof Freeze) {
                return ActorBehavior.ActorTransition.become(state, inactive, List.of());
            }
            return worker().onMessage(state, message, self);
        };
        var states = Map.of(
                PRODUCER, new ActorMachine.Cell<>(EMPTY, producer()),
                WORKER, new ActorMachine.Cell<>(EMPTY, switchable));

        var result = ActorMachine.simulate(states, List.of(toWorker(10),
                        new ActorMachine.Envelope<>(WORKER, new Freeze()),
                        toWorker(11)),
                new ActorMachine.Policy(4, 6, 6));

        assertEquals(List.of(10), result.actor(WORKER).state().seen());
        assertEquals(List.of(10), result.actor(PRODUCER).state().confirmed());
        assertEquals(4, result.processed());
        var second = ActorMachine.simulate(result.actors(), List.of(toWorker(12)),
                new ActorMachine.Policy(2, 2, 2));
        assertEquals(List.of(10), second.actor(WORKER).state().seen(),
                "the replacement behavior is retained by the simulation snapshot");
    }

    @Test
    void maxMessagesStopsWithAnOrderedPendingSnapshot() {
        var result = ActorMachine.simulate(registry(),
                List.of(toWorker(1), toWorker(2)),
                new ActorMachine.Policy(4, 1, 6));
        assertTrue(result.exhausted());
        assertEquals(1, result.processed());
        assertEquals(List.of(toWorker(2),
                new ActorMachine.Envelope<>(PRODUCER, new Acknowledged(1))),
                result.pending());
        assertThrows(UnsupportedOperationException.class, () -> result.pending().clear());
        assertThrows(UnsupportedOperationException.class, () ->
                result.actors().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().clear());
    }

    @Test
    void initialAndProducedMailboxOverflowAreExplicitFailures() {
        assertThrows(IllegalStateException.class, () ->
                ActorMachine.simulate(registry(), List.of(toWorker(1), toWorker(2)),
                        new ActorMachine.Policy(1, 10, 10)));
        ActorBehavior<Memory, Message> fanOut = (state, message, self) ->
                ActorBehavior.ActorTransition.stay(state, List.of(
                        new ActorEffect.Send<>(PRODUCER, new Acknowledged(1)),
                        new ActorEffect.Send<>(PRODUCER, new Acknowledged(2))));
        var actors = Map.of(
                PRODUCER, new ActorMachine.Cell<>(EMPTY, producer()),
                WORKER, new ActorMachine.Cell<>(EMPTY, fanOut));
        assertThrows(IllegalStateException.class, () ->
                ActorMachine.simulate(actors, List.of(toWorker(1)),
                        new ActorMachine.Policy(1, 10, 10)),
                "a transition producing two messages must not exceed mailbox capacity");
    }

    @Test
    void effectBudgetAndUnknownRecipientsAreRejected() {
        assertThrows(IllegalStateException.class, () ->
                ActorMachine.simulate(registry(), List.of(toWorker(1)),
                        new ActorMachine.Policy(3, 10, 1)));
        var missing = new ActorRef<Message>("missing");
        assertThrows(IllegalArgumentException.class, () ->
                ActorMachine.simulate(registry(),
                        List.of(new ActorMachine.Envelope<>(missing, new Freeze())),
                        new ActorMachine.Policy(3, 3, 3)));
        ActorBehavior<Memory, Message> bad = (state, message, self) ->
                ActorBehavior.ActorTransition.stay(state,
                        List.of(new ActorEffect.Send<>(missing, new Freeze())));
        var alternative = Map.of(WORKER, new ActorMachine.Cell<>(EMPTY, bad));
        assertThrows(IllegalArgumentException.class, () ->
                ActorMachine.simulate(alternative, List.of(toWorker(1)),
                        new ActorMachine.Policy(3, 3, 3)));
    }

    @Test
    void originalExceptionsAndStructuralNullnessAreNotSilentlyConverted() {
        var failure = new IllegalStateException("pure step failed");
        ActorBehavior<Memory, Message> throwing = (state, message, self) -> {
            throw failure;
        };
        var alternative = Map.of(WORKER, new ActorMachine.Cell<>(EMPTY, throwing));
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                ActorMachine.simulate(alternative, List.of(toWorker(1)),
                        new ActorMachine.Policy(3, 3, 3))));
        ActorBehavior<Memory, Message> nullTransition = (state, message, self) -> null;
        assertThrows(NullPointerException.class, () ->
                ActorMachine.simulate(Map.of(WORKER,
                                new ActorMachine.Cell<>(EMPTY, nullTransition)),
                        List.of(toWorker(1)), new ActorMachine.Policy(2, 2, 2)));
        assertThrows(NullPointerException.class, () -> new ActorRef<Message>(null));
        assertThrows(IllegalArgumentException.class, () -> new ActorRef<Message>("  "));
        assertThrows(NullPointerException.class, () -> new ActorMachine.Cell<>(EMPTY, null));
        assertThrows(NullPointerException.class, () -> new ActorEffect.Send<>(WORKER, null));
        assertThrows(NullPointerException.class, () -> new ActorEffect.Diagnostic<Message>(null));
        assertThrows(NullPointerException.class, () -> ActorMachine.simulate(null,
                List.of(), new ActorMachine.Policy(1, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new ActorMachine.Policy(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ActorMachine.Policy(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ActorMachine.Policy(1, 1, 0));
    }

    @Test
    void rawDiagnosticDescriptionsRemainPureUntilExplicitInterpretation() {
        var result = ActorMachine.simulate(registry(), List.of(toWorker(2)),
                new ActorMachine.Policy(3, 4, 4));
        AtomicInteger sinkCalls = new AtomicInteger();
        assertEquals(0, sinkCalls.get());
        LogInterpreter.Report report = LogInterpreter.emit(result.diagnostics(), safe ->
                sinkCalls.incrementAndGet(), LogPolicy.strict(3));
        assertEquals(1, sinkCalls.get());
        assertEquals(1, report.accepted());
    }
}
