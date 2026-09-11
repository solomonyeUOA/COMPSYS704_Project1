import java.math.BigInteger;

/** Whole-runtime simulation reset barrier, owned by FaultSupervisorCD. */
public final class M3SystemResetStateV1 {
    private static volatile boolean quarantined;
    private static BigInteger newest = BigInteger.valueOf(-1);
    private static String activeId;
    private static String completedId;
    private static BoundedSignalOfferV1 ackOffer = new BoundedSignalOfferV1(10, 100, 100);
    private static long clearAfterMs;
    private static int executions;
    private static String reconciliation = "NONE";
    private static final long DRAIN_MS = 750;

    private M3SystemResetStateV1() { }

    public static boolean isQuarantined() { return quarantined; }

    public static boolean request(String resetId) {
        return request(resetId, now());
    }

    static synchronized boolean request(String resetId, long nowMs) {
        if (resetId == null || !resetId.matches("RST[0-9]{4,}")) return false;
        BigInteger number = new BigInteger(resetId.substring(3));
        if (number.compareTo(newest) < 0) return false;
        if (number.equals(newest)) {
            // Numerically equivalent but differently encoded IDs are conflicts.
            if (!resetId.equals(activeId)) return false;
            if (resetId.equals(completedId)) ackOffer.arm(completedId, completedId, nowMs);
            return true;
        }
        if (quarantined) return false;
        newest = number;
        activeId = resetId;
        ackOffer = new BoundedSignalOfferV1(10, 100, 100);
        quarantined = true;
        Member3MachineStateV1.stopForSystemReset();
        Member3PlantStateV1.stopForSystemReset();
        clearAfterMs = nowMs + DRAIN_MS;
        return true;
    }

    public static void tick() { tick(now()); }

    static synchronized void tick(long nowMs) {
        if (!quarantined || nowMs < clearAfterMs ||
            !Member3PlantStateV1.isSystemResetSafe()) return;
        // Explicit simulation service action: stop first, then record and remove
        // every workpiece. This is not an assertion about physical hardware.
        reconciliation = Member3PlantStateV1.reconcileSimulatedReset(activeId);
        if (reconciliation == null) return;
        Member3MachineStateV1.finishSystemReset();
        FaultSupervisorStateV2_1.resetRuntimeForSystemReset();
        executions++;
        completedId = activeId;
        ackOffer.arm(completedId, completedId, nowMs);
        quarantined = false;
        System.out.println("M3 SYSTEM RESET SAFE " + completedId + " " + reconciliation);
    }

    public static synchronized String takeAck() {
        return takeAck(now());
    }

    static synchronized String takeAck(long nowMs) {
        return ackOffer.nextReactionValue(nowMs);
    }

    public static synchronized String reconciliation() { return reconciliation; }
    public static synchronized int executions() { return executions; }

    static synchronized void resetForTest() {
        quarantined = false;
        newest = BigInteger.valueOf(-1);
        activeId = completedId = null;
        ackOffer = new BoundedSignalOfferV1(10, 100, 100);
        clearAfterMs = 0;
        executions = 0;
        reconciliation = "NONE";
    }

    private static long now() {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }
}
