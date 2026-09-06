import java.util.Properties;

/** Checks finite generation, timing and configuration without a runtime. */
public final class RecognitionSimulatorSelfTest {
    public static void main(String[] args) {
        Properties properties = settings("2", "L");
        RecognitionSimulatorStateV1 simulator =
            RecognitionSimulatorStateV1.fromProperties(properties, 0L);
        require(simulator.tick(499L, false) == null, "startup delay");
        require("TEST-B001|L".equals(simulator.tick(500L, false)), "first L bottle");
        require(simulator.tick(599L, false) == null, "request pacing");
        require("TEST-B001|L".equals(simulator.tick(600L, false)), "retry same identity");
        require(simulator.tick(700L, true) == null, "complete first context window");
        require(simulator.distributedCount() == 1, "count logical bottle once");
        require(simulator.tick(1699L, false) == null, "inter-bottle spacing");
        require("TEST-B002|L".equals(simulator.tick(1700L, false)), "next unique identity");
        require(simulator.tick(1800L, true) == null && simulator.isFinished(), "finite quantity");
        require(simulator.tick(Long.MAX_VALUE, false) == null, "no automatic restart");

        simulator = RecognitionSimulatorStateV1.fromProperties(settings("1", "S"), 0L);
        require("TEST-B001|S".equals(simulator.tick(500L, false)), "small bottle");
        require(simulator.tick(10499L, false) != null, "request before deadline");
        require(simulator.tick(10500L, false) == null, "deadline stops requests");
        require(simulator.failureReason() != null, "report missing recognition/distribution");
        require(simulator.tick(20000L, true) == null && !simulator.isFinished(),
            "timeout cannot silently resume or skip a bottle");

        simulator = RecognitionSimulatorStateV1.fromProperties(settings("2", "S"), 0L);
        require("TEST-B001|S".equals(simulator.tick(5000L, false)),
            "late startup emits only first bottle");
        simulator.tick(5001L, true);
        require(simulator.tick(5002L, false) == null, "no catch-up burst");

        reject("m4.sim.quantity", "0");
        reject("m4.sim.quantity", "-1");
        reject("m4.sim.quantity", "abc");
        reject("m4.sim.size", "XL");
        reject("m4.sim.bottleIdPrefix", "bad|id");
        reject("m4.sim.startDelayMillis", "-1");
        reject("m4.sim.requestGapMillis", "0");
        reject("m4.sim.timeoutMillis", "100");

        M4BoundedEventV1 event = new M4BoundedEventV1(2, 50L);
        require(!event.isPending(), "empty event is drained");
        event.publish("bottle-1", 0L);
        require(event.isPending() && event.isPending(), "pending observation is read-only");
        require("bottle-1".equals(event.take(0L)) && event.isPending(), "first copy stays pending");
        require(event.take(49L) == null && event.isPending(), "gap stays pending");
        require("bottle-1".equals(event.take(50L)) && !event.isPending(), "final copy drains");

        Member4MachineStateV1.reset();
        require(!Member4MachineStateV1.isContextDistributionComplete("TEST-B001", "S"),
            "unrecognised bottle cannot advance simulator");
        Member4MachineStateV1.acceptRecognition("TEST-B001|S|200");
        require(!Member4MachineStateV1.isContextDistributionComplete("TEST-B001", "S"),
            "registration alone is insufficient while profile copies remain");
        System.out.println("RecognitionSimulatorSelfTest PASSED");
    }

    private static Properties settings(String quantity, String size) {
        Properties properties = new Properties();
        properties.setProperty("m4.sim.quantity", quantity);
        properties.setProperty("m4.sim.size", size);
        properties.setProperty("m4.sim.bottleIdPrefix", "TEST-B");
        properties.setProperty("m4.sim.startDelayMillis", "500");
        return properties;
    }

    private static void reject(String key, String value) {
        Properties properties = settings("1", "S");
        properties.setProperty(key, value);
        try {
            RecognitionSimulatorStateV1.fromProperties(properties, 0L);
            throw new AssertionError("accepted invalid " + key + "=" + value);
        } catch (IllegalArgumentException expected) {
            // Invalid configuration must fail before any bottle is generated.
        }
    }

    private static void require(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }
}
