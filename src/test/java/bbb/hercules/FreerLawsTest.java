package bbb.hercules;

import org.instancio.junit.Given;
import org.instancio.junit.InstancioExtension;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(InstancioExtension.class)
class FreerLawsTest {

    private static LogEvent diagnostic(String name) {
        return new LogEvent(LogEvent.Level.INFO, name,
                Instant.parse("2026-11-15T09:00:00Z"), "FreerTest",
                Optional.of(UUID.fromString("12345678-1234-1234-1234-123456789abc")),
                Optional.empty(), Map.of(
                "token", new LogField(LogField.Visibility.SECRET,
                        new LogValue.Text("SECRET-CREDENTIAL")),
                "count", new LogField(LogField.Visibility.PUBLIC,
                        new LogValue.Count(3))));
    }

    private static <A extends @Nullable Object> FreerInterpreter.Simulation<A> observe(
            Freer<A> program) {
        return FreerInterpreter.simulate(program, Map.of("symbol", "BTCUSD"));
    }

    @RepeatedTest(64)
    void functorIdentityAndComposition(
            @Given Integer number, @Given Integer offset) {
        Freer<Integer> program = Freer.lift(new Instruction.Query("symbol"))
                .map(symbol -> number + (symbol.isPresent() ? offset : 0));
        Function<Integer, Integer> f = n -> n * 2;
        Function<Integer, String> g = n -> "result:" + n;

        assertEquals(observe(program), observe(program.map(Function.identity())));
        assertEquals(observe(program.map(f).map(g)),
                observe(program.map(f.andThen(g))));
    }

    @RepeatedTest(64)
    void monadLeftAndRightIdentity(
            @Given Integer number, @Given String label) {
        Function<Integer, Freer<String>> next = value ->
                Freer.lift(new Instruction.Update("value", label + value))
                        .map(ignored -> label + "/" + value);
        Freer<Integer> original = Freer.lift(new Instruction.Query("symbol"))
                .map(found -> number + (found.isPresent() ? 1 : 0));

        assertEquals(observe(next.apply(number)),
                observe(Freer.pure(number).flatMap(next)));
        assertEquals(observe(original),
                observe(original.flatMap(Freer::pure)));
    }

    @RepeatedTest(64)
    void monadAssociativityPreservesTypedOutputsAndEffectOrder(
            @Given Integer initial, @Given String label) {
        Freer<Integer> start = Freer.lift(new Instruction.Query("symbol"))
                .map(found -> initial + (found.isPresent() ? 1 : 0));
        Function<Integer, Freer<String>> f = n -> Freer.lift(
                        new Instruction.Update("last", label + n))
                .map(ignored -> label + n);
        Function<String, Freer<Integer>> g = s -> Freer.lift(
                        new Instruction.Emit(diagnostic("freer.sequenced")))
                .map(ignored -> s.length());

        var left = start.flatMap(f).flatMap(g);
        var right = start.flatMap(n -> f.apply(n).flatMap(g));
        assertEquals(observe(left), observe(right));
        assertEquals("freer.sequenced", observe(left).events().get(0).name());
        assertEquals(label + (initial + 1), observe(left).state().get("last"));
    }

    @Test
    void typedInstructionResultsAndTwoInterpretersAgreeOnProgramValueAndState() {
        LogEvent event = diagnostic("symbol.queried");
        Freer<Optional<String>> program = Freer.lift(new Instruction.Query("symbol"))
                .flatMap(found -> Freer.lift(new Instruction.Update(
                        "last", found.orElse("missing")))
                        .flatMap(ignored -> Freer.lift(new Instruction.Emit(event))
                                .flatMap(emitted -> Freer.lift(new Instruction.Query("last")))));

        assertEquals(Freer.Shape.BIND, program.shape());
        assertEquals(Freer.Shape.SUSPENDED,
                Freer.lift(new Instruction.Query("last")).shape());
        assertEquals(Freer.Shape.PURE, Freer.pure("done").shape());

        Map<String, String> initial = Map.of("symbol", "ETHUSD");
        var simulated = FreerInterpreter.simulate(program, initial);
        var delivered = new ArrayList<LogEvent>();
        var emitted = FreerInterpreter.emit(program, initial, delivered::add);

        assertEquals(Optional.of("ETHUSD"), simulated.value());
        assertEquals(simulated.value(), emitted.value());
        assertEquals(simulated.state(), emitted.state());
        assertEquals("ETHUSD", emitted.state().get("last"));
        assertEquals(Map.of("symbol", "ETHUSD"), initial,
                "initial map is not mutated");
        assertEquals(List.of(event), simulated.events());
        assertEquals(1, emitted.emitted());
        assertEquals(1, delivered.size());
        assertEquals("[REDACTED]",
                ((LogValue.Text) delivered.get(0).fields().get("token").value()).value());
        assertEquals("SECRET-CREDENTIAL",
                ((LogValue.Text) simulated.events().get(0).fields().get("token").value()).value());
        assertThrows(UnsupportedOperationException.class,
                () -> simulated.state().put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> simulated.events().clear());
    }

    @Test
    void pureProgramConstructionNeverInvokesHandlerOrContinuation() {
        AtomicInteger evaluations = new AtomicInteger();
        Freer<String> pending = Freer.lift(new Instruction.Query("x"))
                .flatMap(found -> {
                    evaluations.incrementAndGet();
                    return Freer.pure(found.orElse("absent"));
                });

        assertEquals(0, evaluations.get());
        assertEquals("absent", observe(pending).value());
        assertEquals(1, evaluations.get());
        assertEquals("absent", observe(pending).value());
        assertEquals(2, evaluations.get(), "interpretation is not memoized");
    }

    @Test
    void heterogeneousInstructionHandlersArePolymorphicWithoutCasts() {
        Instruction.Handler handler = new Instruction.Handler() {
            @Override public Optional<String> query(String key) {
                return Optional.of("queried:" + key);
            }
            @Override public void update(String key, String value) { }
            @Override public void emit(LogEvent event) { }
        };
        assertEquals(Optional.of("queried:x"),
                new Instruction.Query("x").execute(handler));
        assertEquals(State.Unit.INSTANCE,
                new Instruction.Update("x", "y").execute(handler));
        assertEquals(State.Unit.INSTANCE,
                new Instruction.Emit(diagnostic("hello")).execute(handler));
    }

    @Test
    void strictStructuralNullnessAndExplicitlyNullableResults() {
        assertThrows(NullPointerException.class, () -> Freer.lift(null));
        assertThrows(NullPointerException.class, () -> new Freer.Suspend<>(null));
        assertThrows(NullPointerException.class, () -> Freer.pure(1).map(null));
        assertThrows(NullPointerException.class, () -> Freer.pure(1).flatMap(null));
        assertThrows(NullPointerException.class, () -> Freer.pure(1).interpret(null));
        assertThrows(NullPointerException.class, () -> new Instruction.Query(null));
        assertThrows(NullPointerException.class, () -> new Instruction.Update(null, "value"));
        assertThrows(NullPointerException.class, () -> new Instruction.Update("key", null));
        assertThrows(NullPointerException.class, () -> new Instruction.Emit(null));
        assertThrows(NullPointerException.class, () ->
                FreerInterpreter.simulate(null, Map.of()));
        assertThrows(NullPointerException.class, () ->
                FreerInterpreter.simulate(Freer.pure(1), null));
        assertThrows(NullPointerException.class, () ->
                FreerInterpreter.emit(Freer.pure(1), Map.of(), null));
        assertThrows(NullPointerException.class, () -> Freer.pure(1)
                .flatMap(ignored -> null).interpret(silentHandler()));
        assertThrows(NullPointerException.class, () ->
                Freer.lift(new Instruction.Query("a")).interpret(new Instruction.Handler() {
                    @Override public Optional<String> query(String key) { return null; }
                    @Override public void emit(LogEvent event) { }
                    @Override public void update(String key, String value) { }
                }));

        Freer<@Nullable String> nullable = Freer.pure(null);
        assertNull(nullable.interpret(silentHandler()));
        assertEquals("empty", nullable.map(s -> s == null ? "empty" : s)
                .interpret(silentHandler()));
    }

    private static Instruction.Handler silentHandler() {
        return new Instruction.Handler() {
            @Override public Optional<String> query(String key) { return Optional.empty(); }
            @Override public void emit(LogEvent event) { }
            @Override public void update(String key, String value) { }
        };
    }

    @Test
    void explicitSinkFailurePropagatesUnwrappedWithoutReturningPartialResult() {
        var problem = new IllegalStateException("backend failed");
        var event = diagnostic("backend.failure");
        var program = Freer.lift(new Instruction.Emit(event))
                .flatMap(unit -> Freer.lift(new Instruction.Query("x")));
        assertSame(problem, assertThrows(IllegalStateException.class,
                () -> FreerInterpreter.emit(program, Map.of(),
                        safe -> { throw problem; })));
    }

    @Test
    void oneMillionLeftAssociatedBindsAreStackSafe() {
        Freer<Integer> program = Freer.pure(0);
        for (int i = 0; i < 1_000_000; i++) {
            program = program.flatMap(n -> Freer.pure(n + 1));
        }
        assertEquals(1_000_000, observe(program).value());
    }

    @Test
    void deepHeterogeneousSuspensionsPreserveUpdateOrderAndStackSafety() {
        Freer<Integer> program = Freer.pure(0);
        for (int i = 0; i < 120_000; i++) {
            program = program.flatMap(n -> Freer.lift(
                            new Instruction.Update("count", Integer.toString(n + 1)))
                    .map(ignored -> n + 1));
        }
        var outcome = FreerInterpreter.simulate(program, Map.of());
        assertEquals(120_000, outcome.value());
        assertEquals("120000", outcome.state().get("count"));
    }
}
