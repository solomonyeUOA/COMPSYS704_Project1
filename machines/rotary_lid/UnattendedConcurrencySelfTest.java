import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded concurrency regression for GUI injection, controller ticks and observation. */
public final class UnattendedConcurrencySelfTest {
    public static void main(String[] args) throws Exception {
        int rounds = args.length == 0 ? 3 : Integer.parseInt(args[0]);
        if (rounds < 1 || rounds > 100) throw new IllegalArgumentException("rounds must be 1..100");
        for (int i = 0; i < rounds; i++) runRound();
        System.out.println("CONCURRENCY STRESS PASS rounds=" + rounds);
    }

    private static void runRound() throws Exception {
        Member3MachineStateV1.reset();
        Member3PlantStateV1.reset();
        SystemWatchdogV1.resetForTest(System.currentTimeMillis());
        FaultGuiActionsV2_1.setTestMode(true);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final CountDownLatch start = new CountDownLatch(1);
        Runnable[] tasks = {
            () -> { for (int i = 0; i < 20000; i++) {
                FaultInjectionStateV2_1.arm("ALIGNMENT_TIMEOUT");
                FaultInjectionStateV2_1.cancel();
            } },
            () -> { for (int i = 0; i < 20000; i++) {
                if (i % 64 == 0) Member3MachineStateV1.systemReset();
                Member3MachineStateV1.requestRotation(true);
                Member3MachineStateV1.tickRotary(1, false);
                Member3MachineStateV1.takeRotationDoneEvent();
            } },
            () -> { for (int i = 0; i < 2000; i++) {
                FaultMonitoringStateV2_1.snapshot();
                Member3MachineStateV1.controllerRedundancySnapshot();
                Member3PlantStateV1.nextRedundancyTelemetry();
            } }
        };
        Thread[] workers = new Thread[tasks.length];
        for (int i = 0; i < tasks.length; i++) {
            final Runnable task = tasks[i];
            workers[i] = new Thread(() -> {
                try { start.await(); task.run(); }
                catch (Throwable t) { failure.compareAndSet(null, t); }
            }, "ft-stress-" + i);
            workers[i].setDaemon(true);
            workers[i].start();
        }
        start.countDown();
        long deadline = System.nanoTime() + 20000000000L;
        for (Thread worker : workers) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) worker.join(Math.max(1L, remaining / 1000000L));
        }
        for (Thread worker : workers) if (worker.isAlive()) {
            for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true))
                System.err.println(info);
            throw new AssertionError("Concurrent FT operations did not complete within 20 seconds");
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        System.out.println("UnattendedConcurrencySelfTest PASS: 20000 injection/cancellation pairs, 20000 ticks, 2000 snapshots");
    }
}
