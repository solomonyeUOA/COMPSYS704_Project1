import java.util.HashSet;
import java.util.Set;

/** JVM-lifetime bottle and batch tombstones; reset must never clear these. */
public final class M4ResetFenceV1 {
    private static volatile boolean quarantined;
    private static final Set<String> observedBottles = new HashSet<String>();
    private static final Set<String> retiredBottles = new HashSet<String>();
    private static final Set<String> observedBatches = new HashSet<String>();
    private static final Set<String> retiredBatches = new HashSet<String>();
    private static final Set<String> retiredPrefixes = new HashSet<String>();

    private M4ResetFenceV1() { }

    public static boolean isQuarantined() { return quarantined; }

    public static synchronized void begin() {
        quarantined = true;
        retiredBottles.addAll(observedBottles);
        observedBottles.clear();
        retiredBatches.addAll(observedBatches);
        observedBatches.clear();
    }

    public static void release() { quarantined = false; }

    public static synchronized void retirePrefix(String prefix) {
        retiredPrefixes.add(prefix);
    }

    public static synchronized void retireBatch(String batchId) {
        if (batchId != null) { retiredBatches.add(batchId); }
    }

    /** Admits a product-batch contract and remembers it across reset. */
    public static synchronized boolean acceptBatch(String batchId) {
        if (batchId == null) { return false; }
        if (quarantined) {
            retiredBatches.add(batchId);
            return false;
        }
        if (retiredBatches.contains(batchId)) { return false; }
        observedBatches.add(batchId);
        return true;
    }

    /** Checks both independent identities on a correlated recognition. */
    public static synchronized boolean acceptRecognition(
        String bottleId,
        String batchId
    ) {
        if (!acceptBottle(bottleId)) {
            if (quarantined && batchId != null) {
                retiredBatches.add(batchId);
            }
            return false;
        }
        if (batchId == null || retiredBatches.contains(batchId)) {
            return false;
        }
        if (quarantined) {
            retiredBatches.add(batchId);
            return false;
        }
        observedBatches.add(batchId);
        return true;
    }

    public static synchronized boolean accept(String payload) {
        if (payload == null) { return false; }
        String bottle = payload.split("\\|", -1)[0];
        return acceptBottle(bottle);
    }

    private static boolean acceptBottle(String bottle) {
        if (quarantined) {
            retiredBottles.add(bottle);
            return false;
        }
        if (retiredBottles.contains(bottle)) { return false; }
        for (String prefix : retiredPrefixes) {
            if (bottle.startsWith(prefix)) { return false; }
        }
        observedBottles.add(bottle);
        return true;
    }
}
