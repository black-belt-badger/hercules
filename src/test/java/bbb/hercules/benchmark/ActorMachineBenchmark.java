package bbb.hercules.benchmark;

import bbb.hercules.ActorBehavior;
import bbb.hercules.ActorMachine;
import bbb.hercules.ActorRef;
import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Manual, non-CI benchmark comparing a plain loop with deterministic
 * mailbox delivery. The loop is intentionally simpler and cheaper.
 *
 * <p>Run after test compilation:
 * {@code java -cp target/classes:target/test-classes
 * bbb.hercules.benchmark.ActorMachineBenchmark 100000}.
 * These are diagnostic timings, not JMH measurements or performance laws.
 */
public final class ActorMachineBenchmark {

    private static final ThreadMXBean MX = allocator();
    private static volatile long consumed;

    private ActorMachineBenchmark() {
    }

    private record Tick(int delta) { }
    private record Counter(int value) { }

    public static void main(String[] args) {
        int count = args.length == 0 ? 100_000 : Integer.parseInt(args[0]);
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        ActorRef<Tick> address = new ActorRef<>("counter");
        ArrayList<ActorMachine.Envelope<Tick>> messages = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            messages.add(new ActorMachine.Envelope<>(address, new Tick(1)));
        }
        List<ActorMachine.Envelope<Tick>> input = List.copyOf(messages);
        ActorBehavior<Counter, Tick> behavior = (state, tick, self) ->
                ActorBehavior.ActorTransition.stay(
                        new Counter(state.value() + tick.delta()), List.of());
        var actors = Map.of(address, new ActorMachine.Cell<>(new Counter(0), behavior));
        var policy = new ActorMachine.Policy(count, count, 1);

        direct(input);
        ActorMachine.simulate(actors, input, policy);
        System.out.println("JVM=" + System.getProperty("java.version")
                + " messages=" + count);
        System.out.printf("%-26s %12s %18s %12s%n",
                "Operation", "time (ms)", "allocated bytes", "result");
        show("Direct state loop", measure(() -> direct(input)));
        show("Actor FIFO simulation", measure(() ->
                ActorMachine.simulate(actors, input, policy).actor(address).state().value()));
        System.out.println("checksum=" + consumed);
    }

    private static long direct(List<ActorMachine.Envelope<Tick>> messages) {
        int value = 0;
        for (ActorMachine.Envelope<Tick> envelope : messages) {
            value += envelope.message().delta();
        }
        return value;
    }

    private static Sample measure(LongSupplier run) {
        long before = allocated();
        long start = System.nanoTime();
        long value = run.getAsLong();
        return new Sample(System.nanoTime() - start, allocated() - before, value);
    }

    private static void show(String label, Sample sample) {
        consumed += sample.value();
        System.out.printf("%-26s %12.3f %18d %12d%n",
                label, sample.nanos() / 1_000_000.0, sample.bytes(), sample.value());
    }

    private static ThreadMXBean allocator() {
        var platform = ManagementFactory.getThreadMXBean();
        if (!(platform instanceof ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) {
            return null;
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        return bean;
    }

    private static long allocated() {
        return MX == null ? 0 : MX.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    private record Sample(long nanos, long bytes, long value) { }
}
