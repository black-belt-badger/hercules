package bbb.hercules.consumer;

import bbb.hercules.Freer;
import bbb.hercules.FreerInterpreter;
import bbb.hercules.Instruction;
import bbb.hercules.LogEvent;
import bbb.hercules.LogField;
import bbb.hercules.LogValue;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Public-client program descriptions and two explicitly selected interpreters. */
class FreerPublicApiTest {

    private static LogEvent notice() {
        return new LogEvent(LogEvent.Level.INFO, "marketdata.queried",
                Instant.parse("2026-11-15T09:10:00Z"), "Scanner",
                Optional.of(UUID.fromString("12345678-1234-1234-1234-123456789abc")),
                Optional.empty(), Map.of(
                "symbol", new LogField(LogField.Visibility.PUBLIC,
                        new LogValue.Text("BTCUSD")),
                "credential", new LogField(LogField.Visibility.SECRET,
                        new LogValue.Text("SECRET-FROM-DOMAIN"))));
    }

    private static Freer<Optional<String>> program() {
        return Freer.lift(new Instruction.Query("symbol"))
                .flatMap(symbol -> Freer.lift(new Instruction.Update(
                        "last", symbol.orElse("none")))
                        .flatMap(ignored -> Freer.lift(new Instruction.Emit(notice()))
                                .flatMap(event -> Freer.lift(new Instruction.Query("last")))));
    }

    @Test
    void programIsDataUntilSelectedInterpreterRuns() {
        Freer<Optional<String>> computation = program();
        AtomicInteger external = new AtomicInteger();
        Map<String, String> initial = Map.of("symbol", "BTCUSD");
        assertEquals(0, external.get());

        FreerInterpreter.Simulation<Optional<String>> simulated =
                FreerInterpreter.simulate(computation, initial);
        assertEquals(Optional.of("BTCUSD"), simulated.value());
        assertEquals(Map.of("symbol", "BTCUSD", "last", "BTCUSD"), simulated.state());
        assertEquals(List.of(notice()), simulated.events());
        assertEquals(0, external.get(), "simulation cannot call external sinks");

        List<LogEvent> accepted = new ArrayList<>();
        FreerInterpreter.Emission<Optional<String>> emitted =
                FreerInterpreter.emit(computation, initial, event -> {
                    external.incrementAndGet();
                    accepted.add(event);
                });
        assertEquals(Optional.of("BTCUSD"), emitted.value());
        assertEquals(simulated.state(), emitted.state());
        assertEquals(1, emitted.emitted());
        assertEquals(1, external.get());
        assertEquals("[REDACTED]",
                ((LogValue.Text) accepted.get(0).fields().get("credential").value()).value());
        assertEquals("SECRET-FROM-DOMAIN",
                ((LogValue.Text) simulated.events().get(0)
                        .fields().get("credential").value()).value());
        assertEquals(Map.of("symbol", "BTCUSD"), initial);
    }

    @Test
    void reusableProgramCanBeInterpretedAgainstDifferentInitialState() {
        var computation = program();
        assertEquals(Optional.of("SOLUSD"),
                FreerInterpreter.simulate(computation, Map.of("symbol", "SOLUSD")).value());
        assertEquals(Optional.of("none"),
                FreerInterpreter.simulate(computation, Map.of()).value());
        assertEquals("none", FreerInterpreter.simulate(computation, Map.of())
                .state().get("last"));
    }

    @Test
    void plainPureProgramNeedsNoEffectsAndCanCarryNullableValue() {
        Freer<@org.jspecify.annotations.Nullable String> result = Freer.pure(null);
        assertNull(FreerInterpreter.simulate(result, Map.of()).value());
        assertEquals(Map.of(), FreerInterpreter.simulate(result, Map.of()).state());
    }
}
