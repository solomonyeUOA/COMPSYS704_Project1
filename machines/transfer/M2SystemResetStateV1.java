import java.math.BigInteger;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/** Whole-member simulation reset: quarantine, safe Plant, runtime clear, ACK. */
public final class M2SystemResetStateV1 {
    private static volatile boolean quarantined;
    private static volatile String generation = "0";
    private static BigInteger latest = BigInteger.ZERO;
    private static String activeId;
    private static String completedId;
    private static M2BoundedSignalOfferV1 ack = new M2BoundedSignalOfferV1(10, 100L);
    private static boolean runtimeCleared;
    private static boolean settleReaction;
    private static int resetCount;
    private static final Set<String> observed = Collections.newSetFromMap(
        new ConcurrentHashMap<String, Boolean>());
    private static final Set<String> retired = Collections.newSetFromMap(
        new ConcurrentHashMap<String, Boolean>());
    private static final Set<String> retiredBatches = Collections.newSetFromMap(
        new ConcurrentHashMap<String, Boolean>());

    private M2SystemResetStateV1() { }

    public static synchronized boolean request(String resetId) {
        return request(resetId, System.currentTimeMillis());
    }

    public static synchronized boolean request(String resetId, long nowMillis) {
        if (resetId == null || !resetId.matches("RST[0-9]{4,}")) {
            return false;
        }
        BigInteger number = new BigInteger(resetId.substring(3));
        if (number.compareTo(latest) < 0 ||
            (number.equals(latest) && activeId != null &&
             !resetId.equals(activeId))) {
            return false;
        }
        if (resetId.equals(activeId)) {
            if (resetId.equals(completedId)) {
                ack.arm(resetId, resetId, nowMillis);
            }
            return true;
        }
        latest = number;
        activeId = resetId;
        completedId = null;
        ack = new M2BoundedSignalOfferV1(10, 100L);
        runtimeCleared = false;
        quarantined = true;
        generation = number.add(BigInteger.ONE).toString();
        // The simulated actuators are explicitly de-energised before any
        // controller state is discarded. Hardware adapters must implement
        // the same confirmed-safe contract before returning true here.
        M2PlantStateV1.safeStopForSystemReset();
        clearRuntimeIfSafe();
        return true;
    }

    private static void clearRuntimeIfSafe() {
        if (runtimeCleared || !M2PlantStateV1.isSafeInitialState()) { return; }
        M2MachineStateV1.resetForSystemReset();
        retired.addAll(observed);
        for (String id : observed) {
            String batch = batchOf(id);
            if (batch != null) { retiredBatches.add(batch); }
        }
        observed.clear();
        M2TransferFaultAdapterStateV2_1.resetForSystemReset();
        DigitalTwinStateV1.resetForSystemReset();
        M2TwinViewerStateV1.reset();
        resetCount++;
        runtimeCleared = true;
        settleReaction = true;
    }

    /** A separate reaction verifies that cancelled commands stay cancelled. */
    public static synchronized void tick() {
        tick(System.currentTimeMillis());
    }

    public static synchronized void tick(long nowMillis) {
        if (!quarantined) { return; }
        if (!runtimeCleared) {
            M2PlantStateV1.safeStopForSystemReset();
            clearRuntimeIfSafe();
            return;
        }
        if (settleReaction) { settleReaction = false; return; }
        if (!M2PlantStateV1.isSafeInitialState()) { return; }
        completedId = activeId;
        ack.arm(completedId, completedId, nowMillis);
        quarantined = false;
    }

    public static synchronized String takeAck() {
        return takeAck(System.currentTimeMillis());
    }

    public static synchronized String takeAck(long nowMillis) {
        return quarantined ? null : ack.nextReactionValue(nowMillis);
    }

    public static boolean isQuarantined() { return quarantined; }
    public static String getGeneration() { return generation; }
    public static synchronized int getResetCount() { return resetCount; }

    public static boolean allowBottle(String id) {
        if (quarantined || id == null || retired.contains(id)) { return false; }
        String batch = batchOf(id);
        return batch == null || !retiredBatches.contains(batch);
    }

    public static void observeBottle(String id) {
        if (id != null && !"-".equals(id)) { observed.add(id); }
    }

    private static String batchOf(String id) {
        if (id == null) { return null; }
        int suffix = id.lastIndexOf("-B");
        return suffix > 0 && id.substring(suffix + 2).matches("[0-9]+") ?
            id.substring(0, suffix) : null;
    }
}
