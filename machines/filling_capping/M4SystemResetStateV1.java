import java.math.BigInteger;
import java.util.Iterator;
import java.util.LinkedHashSet;

/** Reset barrier owner. ACK follows simulated actuator evidence, never receipt. */
public final class M4SystemResetStateV1 {
    private static String latestId;
    private static final int RESET_HISTORY_LIMIT = 64;
    private static final LinkedHashSet<String> seenResetIds =
        new LinkedHashSet<String>();
    private static boolean resetting;
    private static long resetCount;
    private static final M4BoundedEventV1 ack = new M4BoundedEventV1(10, 100L);

    private M4SystemResetStateV1() { }

    public static synchronized boolean request(String resetId) {
        return request(resetId, System.currentTimeMillis());
    }

    public static synchronized boolean request(String resetId, long now) {
        if (resetId == null || !resetId.matches("RST[0-9]{4,}")) {
            return false;
        }
        BigInteger number = new BigInteger(resetId.substring(3));
        if (resetId.equals(latestId)) {
            if (!resetting) { ack.publish(resetId, now); }
            return true;
        }
        if (hasSeenReset(resetId, number)) { return false; }
        rememberReset(resetId);
        // A new reset can supersede an in-progress request. Safety motion
        // continues and only the active correlation ID can be acknowledged.
        latestId = resetId;
        ack.cancel();
        if (!resetting) {
            resetting = true;
            resetCount++;
            M4ResetFenceV1.begin();
            RecognitionSimulatorStateV1.resetForSystem();
            Member4MachineStateV1.beginSystemReset();
            Member4PlantStateV1.beginSystemReset(now);
        }
        System.out.println("[M4-RESET] quarantine " + resetId);
        return true;
    }

    public static synchronized void tick() {
        tick(System.currentTimeMillis());
    }

    public static synchronized void tick(long now) {
        if (!resetting) { return; }
        Member4PlantStateV1.tickSystemReset(now);
        if (!Member4PlantStateV1.isSystemResetSafe()) { return; }
        Member4MachineStateV1.completeSystemReset();
        resetting = false;
        M4ResetFenceV1.release();
        ack.publish(latestId, now);
        System.out.println("[M4-RESET] safe ACK " + latestId + " " +
            Member4PlantStateV1.systemResetEvidence());
    }

    public static synchronized String takeAck() {
        return takeAck(System.currentTimeMillis());
    }

    public static synchronized String takeAck(long now) {
        return resetting ? null : ack.take(now);
    }

    public static synchronized long resetCount() { return resetCount; }

    private static boolean hasSeenReset(String resetId, BigInteger number) {
        for (String seen : seenResetIds) {
            if (seen.equals(resetId) ||
                new BigInteger(seen.substring(3)).equals(number)) {
                return true;
            }
        }
        return false;
    }

    private static void rememberReset(String resetId) {
        if (seenResetIds.size() >= RESET_HISTORY_LIMIT) {
            Iterator<String> oldest = seenResetIds.iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        seenResetIds.add(resetId);
    }
}
