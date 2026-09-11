import java.math.BigInteger;

/** Reset barrier owner. ACK follows simulated actuator evidence, never receipt. */
public final class M4SystemResetStateV1 {
    private static String latestId;
    private static BigInteger latestNumber;
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
        if (latestNumber != null && number.compareTo(latestNumber) <= 0) {
            if (!resetId.equals(latestId)) { return false; }
            if (!resetting) { ack.publish(resetId, now); }
            return true;
        }
        // A newer reset can supersede an in-progress request. Safety motion
        // continues and only the newest correlation ID can be acknowledged.
        latestId = resetId;
        latestNumber = number;
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
}
