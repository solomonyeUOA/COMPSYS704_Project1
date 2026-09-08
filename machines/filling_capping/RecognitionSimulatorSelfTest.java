import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Checks batch-driven idempotence plus legacy standalone compatibility. */
public final class RecognitionSimulatorSelfTest {
    private RecognitionSimulatorSelfTest() {
    }

    public static void main(String[] args) {
        batchCaseDQ1ProducesExactlyOneProfile();
        batchCaseEQ3ProducesExactlyThreeProfiles();
        batchCaseFDuplicateDoesNotRestart();
        batchCaseGConflictingQuantityIsRejected();
        batchCaseHNewBatchAfterFinishIsAccepted();
        batchCaseINewBatchWhileActiveIsRejected();
        batchCaseJBottleIdsUseBatchPrefix();
        multiProductOrderUsesIndependentBatches();
        batchQuantityMatrixQ10AndQ20();
        batchCaseKLegacyPropertiesStillWork();
        batchCaseLTimeoutReleasesTheSimulator();
        invalidConfigurationAndTransportRegression();
        System.out.println(
            "RecognitionSimulatorSelfTest PASSED " +
            "(M4 batch cases D-L; legacy compatibility)"
        );
    }

    private static void batchCaseDQ1ProducesExactlyOneProfile() {
        RecognitionSimulatorStateV1 simulator = batchDriven();
        List<String> profiles = generateBatch(
            simulator, "PO0001-P01", 1, 0L
        );
        require(profiles.size() == 1,
            "M4-D q1 must produce exactly one profile");
        require("PO0001-P01-B001|S|200".equals(profiles.get(0)),
            "M4-D q1 profile identity");
    }

    private static void batchCaseEQ3ProducesExactlyThreeProfiles() {
        RecognitionSimulatorStateV1 simulator = batchDriven();
        List<String> profiles = generateBatch(
            simulator, "PO0001-P01", 3, 0L
        );
        require(profiles.size() == 3,
            "M4-E q3 must produce exactly three profiles");
        require("PO0001-P01-B001|S|200".equals(profiles.get(0)),
            "M4-E first profile");
        require("PO0001-P01-B003|S|200".equals(profiles.get(2)),
            "M4-E final profile");
    }

    private static void batchCaseFDuplicateDoesNotRestart() {
        RecognitionSimulatorStateV1 simulator = batchDriven();
        require(simulator.startBatch("PO0002-P01", 3, 0L) ==
            RecognitionSimulatorStateV1.BatchStartResult.ACCEPTED,
            "M4-F initial batch accepted");
        require("PO0002-P01-B001|S".equals(simulator.tick(0L, false)),
            "M4-F first request");
        require(simulator.startBatch("PO0002-P01", 3, 1L) ==
            RecognitionSimulatorStateV1.BatchStartResult.DUPLICATE,
            "M4-F duplicate accepted idempotently");
        require(simulator.distributedCount() == 0 &&
            "PO0002-P01-B001".equals(simulator.currentBottleId()),
            "M4-F duplicate must not reset or advance state");
        simulator.tick(1L, true);
        completeRemaining(simulator, 11L, 2);
        require(simulator.isFinished() && simulator.distributedCount() == 3,
            "M4-F original batch finishes once");
        require(simulator.startBatch("PO0002-P01", 3, 100L) ==
            RecognitionSimulatorStateV1.BatchStartResult.DUPLICATE,
            "M4-F late duplicate stays idempotent");
        require(simulator.tick(100L, false) == null &&
            simulator.distributedCount() == 3,
            "M4-F late duplicate generates no new profile");
    }

    private static void batchCaseGConflictingQuantityIsRejected() {
        RecognitionSimulatorStateV1 simulator = batchDriven();
        require(simulator.startBatch("PO0003-P01", 10, 0L) ==
            RecognitionSimulatorStateV1.BatchStartResult.ACCEPTED,
            "M4-G original quantity accepted");
        require(simulator.startBatch("PO0003-P01", 5, 1L) ==
            RecognitionSimulatorStateV1.BatchStartResult.CONFLICT,
            "M4-G conflicting quantity rejected");
        require(simulator.batchQuantity() == 10 &&
            "PO0003-P01".equals(simulator.activeBatchId()),
            "M4-G conflict must not mutate the active contract");
    }

    private static void batchCaseHNewBatchAfterFinishIsAccepted() {
        RecognitionSimulatorStateV1 simulator = batchDriven();
        generateBatch(simulator, "PO0004-P01", 1, 0L);
        require(simulator.startBatch("PO0004-P02", 2, 20L) ==
            RecognitionSimulatorStateV1.BatchStartResult.ACCEPTED,
            "M4-H next product accepted after finish");
        require("PO0004-P02-B001|S".equals(simulator.tick(20L, false)),
            "M4-H next product starts from B001");
    }

    private static void batchCaseINewBatchWhileActiveIsRejected() {
        RecognitionSimulatorStateV1 simulator = batchDriven();
        require(simulator.startBatch("PO0005-P01", 3, 0L) ==
            RecognitionSimulatorStateV1.BatchStartResult.ACCEPTED,
            "M4-I active batch accepted");
        require(simulator.startBatch("PO0005-P02", 2, 1L) ==
            RecognitionSimulatorStateV1.BatchStartResult.ACTIVE_BATCH,
            "M4-I interleaved batch rejected");
        require("PO0005-P01".equals(simulator.activeBatchId()) &&
            simulator.batchQuantity() == 3,
            "M4-I active batch remains unchanged");
    }

    private static void batchCaseJBottleIdsUseBatchPrefix() {
        RecognitionSimulatorStateV1 simulator = batchDriven();
        List<String> profiles = generateBatch(
            simulator, "PO0042-P02", 3, 0L
        );
        for (int index = 0; index < profiles.size(); index++) {
            String expected = "PO0042-P02-B" +
                String.format(
                    java.util.Locale.ROOT,
                    "%03d",
                    Integer.valueOf(index + 1)
                );
            require(profiles.get(index).startsWith(expected + "|"),
                "M4-J batch-prefixed bottle " + (index + 1));
        }
    }

    private static void multiProductOrderUsesIndependentBatches() {
        RecognitionSimulatorStateV1 simulator = batchDriven();
        List<String> productOne = generateBatch(
            simulator, "PO0001-P01", 10, 0L
        );
        List<String> productTwo = generateBatch(
            simulator, "PO0001-P02", 5, 200L
        );
        require(productOne.size() == 10 && productTwo.size() == 5,
            "multi-product quantities remain independent");
        require("PO0001-P01-B010|S|200".equals(productOne.get(9)),
            "first product finishes with its own B010");
        require("PO0001-P02-B001|S|200".equals(productTwo.get(0)) &&
            "PO0001-P02-B005|S|200".equals(productTwo.get(4)),
            "second product restarts at B001 and finishes at B005");
    }

    private static void batchCaseLTimeoutReleasesTheSimulator() {
        RecognitionSimulatorStateV1 simulator = batchDriven();
        require(simulator.startBatch("PO0007-P01", 3, 0L) ==
            RecognitionSimulatorStateV1.BatchStartResult.ACCEPTED,
            "M4-L first batch accepted");
        require("PO0007-P01-B001|S".equals(simulator.tick(0L, false)),
            "M4-L first request");
        require(simulator.tick(1000L, false) == null &&
            simulator.failureReason() != null,
            "M4-L context distribution times out");
        require(!simulator.isBatchActive() && !simulator.isFinished(),
            "M4-L timed-out batch stops running");
        require(simulator.tick(2000L, false) == null,
            "M4-L timed-out batch produces no further profile");

        // A timeout must not wedge the simulator: M1 only offers a bounded
        // number of copies, so a permanently rejecting M4 would silently kill
        // the link for the rest of the run.
        require(simulator.startBatch("PO0007-P02", 2, 2000L) ==
            RecognitionSimulatorStateV1.BatchStartResult.ACCEPTED,
            "M4-L next batch accepted after a timeout");
        require(simulator.failureReason() == null,
            "M4-L accepted batch clears the previous failure");
        require("PO0007-P02-B001|S".equals(simulator.tick(2000L, false)),
            "M4-L next batch restarts at B001");
        simulator.tick(2001L, true);
        completeRemaining(simulator, 2011L, 1);
        require(simulator.isFinished() && simulator.distributedCount() == 2,
            "M4-L next batch finishes normally");
    }

    private static void batchCaseKLegacyPropertiesStillWork() {
        Properties properties = legacySettings("2", "L");
        RecognitionSimulatorStateV1 simulator =
            RecognitionSimulatorStateV1.fromProperties(properties, 0L);
        require(simulator.tick(499L, false) == null,
            "M4-K legacy startup delay");
        require("TEST-B001|L".equals(simulator.tick(500L, false)),
            "M4-K legacy first bottle");
        require(simulator.tick(599L, false) == null,
            "M4-K legacy request pacing");
        require("TEST-B001|L".equals(simulator.tick(600L, false)),
            "M4-K legacy retry identity");
        require(simulator.tick(700L, true) == null,
            "M4-K legacy first context complete");
        require("TEST-B002|L".equals(simulator.tick(1700L, false)),
            "M4-K legacy second identity");
        require(simulator.tick(1800L, true) == null &&
            simulator.isFinished(), "M4-K finite legacy quantity");
        require(simulator.tick(Long.MAX_VALUE, false) == null,
            "M4-K legacy batch never auto-restarts");

        Properties integrated = batchSettings();
        integrated.setProperty("m4.sim.quantity", "not-used");
        simulator = RecognitionSimulatorStateV1.batchDrivenFromProperties(
            integrated, 0L
        );
        require(!simulator.isBatchActive() &&
            simulator.startBatch("PO0006-P01", 1, 0L) ==
                RecognitionSimulatorStateV1.BatchStartResult.ACCEPTED,
            "M4-K integrated mode ignores legacy quantity property");
    }

    private static void batchQuantityMatrixQ10AndQ20() {
        List<String> q10 = generateBatch(
            batchDriven(), "PO0010-P01", 10, 0L
        );
        List<String> q20 = generateBatch(
            batchDriven(), "PO0020-P01", 20, 0L
        );
        require(q10.size() == 10,
            "integrated quantity matrix q10 profile count");
        require(q20.size() == 20,
            "integrated quantity matrix q20 profile count");
        require("PO0020-P01-B020|S|200".equals(q20.get(19)),
            "integrated q20 final profile identity");
        System.out.println(
            "M4_AUTO_PROFILE_COUNTS q1=1 q3=3 q10=" + q10.size() +
            " q20=" + q20.size()
        );
    }

    private static List<String> generateBatch(
        RecognitionSimulatorStateV1 simulator,
        String batchId,
        int quantity,
        long startMillis
    ) {
        require(simulator.startBatch(batchId, quantity, startMillis) ==
            RecognitionSimulatorStateV1.BatchStartResult.ACCEPTED,
            "batch must be accepted: " + batchId);
        List<String> profiles = new ArrayList<String>();
        long now = startMillis;
        for (int bottle = 1; bottle <= quantity; bottle++) {
            String request = simulator.tick(now, false);
            require(request != null, "missing request for bottle " + bottle);
            String profile = RecognitionPlantModelV1.recognise(request);
            require(profile != null, "recognition failed for " + request);
            profiles.add(profile);
            require(simulator.tick(now + 1L, true) == null,
                "context acknowledgement must not emit a request");
            now += 11L;
        }
        require(simulator.isFinished(), "batch must finish exactly");
        require(simulator.tick(now + 1000L, false) == null,
            "finished batch must stay idle");
        return profiles;
    }

    private static void completeRemaining(
        RecognitionSimulatorStateV1 simulator,
        long firstMillis,
        int remaining
    ) {
        long now = firstMillis;
        for (int index = 0; index < remaining; index++) {
            require(simulator.tick(now, false) != null,
                "remaining bottle request " + index);
            simulator.tick(now + 1L, true);
            now += 11L;
        }
    }

    private static RecognitionSimulatorStateV1 batchDriven() {
        return RecognitionSimulatorStateV1.batchDrivenFromProperties(
            batchSettings(), 0L
        );
    }

    private static Properties batchSettings() {
        Properties properties = new Properties();
        properties.setProperty("m4.sim.size", "S");
        properties.setProperty("m4.sim.intervalMillis", "10");
        properties.setProperty("m4.sim.requestGapMillis", "1");
        properties.setProperty("m4.sim.timeoutMillis", "1000");
        return properties;
    }

    private static Properties legacySettings(String quantity, String size) {
        Properties properties = batchSettings();
        properties.setProperty("m4.sim.quantity", quantity);
        properties.setProperty("m4.sim.size", size);
        properties.setProperty("m4.sim.bottleIdPrefix", "TEST-B");
        properties.setProperty("m4.sim.startDelayMillis", "500");
        properties.setProperty("m4.sim.intervalMillis", "1000");
        properties.setProperty("m4.sim.requestGapMillis", "100");
        properties.setProperty("m4.sim.timeoutMillis", "10000");
        return properties;
    }

    private static void invalidConfigurationAndTransportRegression() {
        rejectLegacy("m4.sim.quantity", "0");
        rejectLegacy("m4.sim.quantity", "-1");
        rejectLegacy("m4.sim.quantity", "abc");
        rejectLegacy("m4.sim.size", "XL");
        rejectLegacy("m4.sim.bottleIdPrefix", "bad|id");
        rejectLegacy("m4.sim.startDelayMillis", "-1");
        rejectLegacy("m4.sim.requestGapMillis", "0");
        rejectLegacy("m4.sim.timeoutMillis", "100");

        RecognitionSimulatorStateV1 simulator = batchDriven();
        require(simulator.startBatchPayload("missing-quantity", 0L) ==
            RecognitionSimulatorStateV1.BatchStartResult.INVALID,
            "invalid batch payload rejected");
        require(simulator.startBatchPayload("PO-P01|0", 0L) ==
            RecognitionSimulatorStateV1.BatchStartResult.INVALID,
            "zero batch quantity rejected");

        M4BoundedEventV1 event = new M4BoundedEventV1(2, 50L);
        require(!event.isPending(), "empty event is drained");
        event.publish("bottle-1", 0L);
        require(event.isPending() && event.isPending(),
            "pending observation is read-only");
        require("bottle-1".equals(event.take(0L)) && event.isPending(),
            "first copy stays pending");
        require(event.take(49L) == null && event.isPending(),
            "gap stays pending");
        require("bottle-1".equals(event.take(50L)) && !event.isPending(),
            "final copy drains");

        Member4MachineStateV1.reset();
        require(!Member4MachineStateV1.isContextDistributionComplete(
            "TEST-B001", "S"),
            "unrecognised bottle cannot advance simulator");
        Member4MachineStateV1.acceptRecognition("TEST-B001|S|200");
        require(!Member4MachineStateV1.isContextDistributionComplete(
            "TEST-B001", "S"),
            "registration alone is insufficient while copies remain");
    }

    private static void rejectLegacy(String key, String value) {
        Properties properties = legacySettings("1", "S");
        properties.setProperty(key, value);
        try {
            RecognitionSimulatorStateV1.fromProperties(properties, 0L);
            throw new AssertionError(
                "accepted invalid legacy " + key + "=" + value
            );
        }
        catch (IllegalArgumentException expected) {
            // Invalid standalone configuration must fail before generation.
        }
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
