package bbb.hercules.benchmark;

import bbb.hercules.Freer;
import bbb.hercules.FreerInterpreter;
import bbb.hercules.Instruction;
import bbb.hercules.LogEvent;
import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Opt-in, non-JUnit comparison: direct typed ADT interpretation versus
 * deferred Freer composition and execution. No timing assertions run in CI.
 *
 * <p>After test compilation:
 * {@code java -cp target/classes:target/test-classes
 * bbb.hercules.benchmark.FreerBenchmark 100000}.
 * Numbers are single-run diagnostics; use JMH for statistically rigorous work.
 */
public final class FreerBenchmark {
    private static final ThreadMXBean MX = allocationBean();
    private static volatile long consumed;

    private FreerBenchmark() {
    }

    public static void main(String[] args) {
        int steps = args.length == 0 ? 100_000 : Integer.parseInt(args[0]);
        if (steps < 1) {
            throw new IllegalArgumentException("step count must be positive");
        }

        // Warm both paths and verify identical results prior to comparison.
        direct(1_000);
        freer(program(1_000));
        String desired = Integer.toString(steps - 1);

        System.out.println("JVM=" + System.getProperty("java.version")
                + ", updates=" + steps);
        System.out.printf("%-30s %11s %16s %14s%n",
                "Operation", "time ms", "allocated bytes", "result length");
        Outcome<List<Instruction.Update>> directBuild =
                measure(() -> instructions(steps));
        Outcome<Freer<StateMarker>> freerBuild =
                measure(() -> program(steps));

        show("Build direct ADT instructions", directBuild);
        show("Build Freer syntax", freerBuild);
        Outcome<String> directRun = measure(() -> execute(directBuild.value()));
        Outcome<String> freerRun = measure(() -> freer(freerBuild.value()));
        show("Interpret direct ADT", directRun);
        show("Interpret Freer", freerRun);
        if (!desired.equals(directRun.value()) || !desired.equals(freerRun.value())) {
            throw new AssertionError("Interpreter state results differ");
        }
        System.out.println("Both paths produced final state counter=" + desired);
        System.out.println("Results are load/JIT/GC dependent, not production performance claims.");
    }

    private enum StateMarker { DONE }

    private static Freer<StateMarker> program(int count) {
        Freer<StateMarker> result = Freer.pure(StateMarker.DONE);
        for (int i = 0; i < count; i++) {
            String value = Integer.toString(i);
            result = result.flatMap(ignored ->
                    Freer.lift(new Instruction.Update("counter", value))
                            .map(unit -> StateMarker.DONE));
        }
        return result;
    }

    private static List<Instruction.Update> instructions(int count) {
        ArrayList<Instruction.Update> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(new Instruction.Update("counter", Integer.toString(i)));
        }
        return List.copyOf(result);
    }

    private static String execute(List<Instruction.Update> instructions) {
        LinkedHashMap<String, String> state = new LinkedHashMap<>();
        Instruction.Handler handler = new Instruction.Handler() {
            @Override public Optional<String> query(String key) {
                return Optional.ofNullable(state.get(key));
            }
            @Override public void update(String key, String value) {
                state.put(key, value);
            }
            @Override public void emit(LogEvent event) {
                throw new UnsupportedOperationException("No emit in this workload");
            }
        };
        for (Instruction.Update instruction : instructions) {
            instruction.execute(handler);
        }
        return state.get("counter");
    }

    private static String direct(int count) {
        return execute(instructions(count));
    }

    private static String freer(Freer<StateMarker> computation) {
        return FreerInterpreter.simulate(computation, Map.of()).state().get("counter");
    }

    private static <A> Outcome<A> measure(Supplier<A> operation) {
        long allocatedBefore = allocated();
        long timeBefore = System.nanoTime();
        A result = operation.get();
        return new Outcome<>(result,
                System.nanoTime() - timeBefore, allocated() - allocatedBefore);
    }

    private static void show(String label, Outcome<?> outcome) {
        String represented = String.valueOf(outcome.value());
        consumed += represented.length();
        System.out.printf("%-30s %11.3f %16d %14d%n",
                label, outcome.nanos() / 1_000_000.0,
                outcome.bytes(), represented.length());
    }

    private static ThreadMXBean allocationBean() {
        var platform = ManagementFactory.getThreadMXBean();
        if (!(platform instanceof ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) {
            return null;
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        return bean;
    }

    private static long allocated() {
        return MX == null ? 0 : MX.getThreadAllocatedBytes(
                Thread.currentThread().getId());
    }

    private record Outcome<A>(A value, long nanos, long bytes) {
    }
}
