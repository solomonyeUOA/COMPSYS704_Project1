/** Deterministic M1 batch identity and bounded retry checks. */
public final class M1M4BatchSyncSelfTest {
    private M1M4BatchSyncSelfTest() {
    }

    public static void main(String[] args) {
        caseARetryKeepsIdenticalBatchId();
        caseBNextProductCreatesNewBatchId();
        caseCSamePayloadDoesNotMutateIdentity();
        caseDInvalidOrderIdSkipsTheTrigger();
        caseEReplacementAndDiscardCancelHeldCopy();
        caseFHeldCopiesRemainIdempotentAtM4();
        caseGSystemResetCancelsHeldBatchWithoutInventingAck();
        System.out.println(
            "M1M4BatchSyncSelfTest PASSED (M1 batch cases A-G; held delivery)"
        );
    }

    private static void caseARetryKeepsIdenticalBatchId() {
        M1SimulationBatchOfferV1 offer =
            new M1SimulationBatchOfferV1(3, 600L);
        require(offer.beginProductBatch("PO0001", 1, 20, "S", 0L),
            "M1-A initial product batch");
        String expected = "PO0001-P01|20|S";
        require(expected.equals(offer.nextReactionValue(0L)),
            "M1-A first payload");
        require(expected.equals(offer.nextReactionValue(1L)) &&
            expected.equals(offer.nextReactionValue(199L)) &&
            offer.getOfferCount() == 1,
            "M1-A first copy remains PRESENT for 200 ms without new attempts");
        require(offer.nextReactionValue(200L) == null &&
            offer.nextReactionValue(799L) == null,
            "M1-A wall-clock ABSENT gap after first held copy");
        require(expected.equals(offer.nextReactionValue(800L)),
            "M1-A second payload is identical");
        require(expected.equals(offer.nextReactionValue(999L)) &&
            offer.getOfferCount() == 2,
            "M1-A second copy stays PRESENT without incrementing attempts");
        require(offer.nextReactionValue(1000L) == null &&
            offer.nextReactionValue(1599L) == null,
            "M1-A ABSENT gap after second held copy");
        require(expected.equals(offer.nextReactionValue(1600L)),
            "M1-A third payload is identical");
        require(expected.equals(offer.nextReactionValue(1799L)),
            "M1-A third copy is held to its deadline");
        require(offer.nextReactionValue(1800L) == null &&
            !offer.isPending() && offer.getOfferCount() == 3,
            "M1-A bounded retry drains after three hold windows");
        require(offer.beginProductBatch("PO0001", 1, 20, "S", 2000L) &&
            offer.nextReactionValue(2000L) == null &&
            offer.getOfferCount() == 3,
            "M1-A duplicate batch after drain cannot restart delivery");
    }

    private static void caseBNextProductCreatesNewBatchId() {
        require(CoordinatorStateV1.accept(
            "PO0100|2|APPLE,S,60,40,1;BERRY,L,25,75,3"
        ), "M1-B multi-product order accepted");
        require("PO0100-P01".equals(
            CoordinatorStateV1.currentM4SimulationBatchId()),
            "M1-B first product batch ID");
        require("PO0100-P01|1|S".equals(
            CoordinatorStateV1.currentM4SimulationBatchPayload()),
            "M1-B first product payload");
        require(CoordinatorStateV1.recordBottleDone(),
            "M1-B first product completes");
        CoordinatorStateV1.advanceToNextProduct();
        require("PO0100-P02".equals(
            CoordinatorStateV1.currentM4SimulationBatchId()),
            "M1-B next product gets a new ID");
        require("PO0100-P02|3|L".equals(
            CoordinatorStateV1.currentM4SimulationBatchPayload()),
            "M1-B next product quantity follows POS order");

        CoordinatorStateV1.nextStatusPollMillis = Long.MAX_VALUE;
        Coordinator coordinator = new Coordinator("M1M4BatchProbe");
        coordinator.init();
        coordinator.M4_SIM_BATCH_REQUEST.setClear();
        coordinator.runClockDomain();
        require(coordinator.M4_SIM_BATCH_REQUEST.getStatus(),
            "M1-B generated Coordinator emits simulation request");
        require("PO0100-P02|3|L".equals(
            coordinator.M4_SIM_BATCH_REQUEST.getValue()),
            "M1-B generated Coordinator carries stable payload");
    }

    private static void caseCSamePayloadDoesNotMutateIdentity() {
        M1SimulationBatchOfferV1 offer =
            new M1SimulationBatchOfferV1(3, 600L);
        require(offer.beginProductBatch("PO0200", 1, 10, "L", 0L),
            "M1-C batch accepted");
        String stable = offer.getStablePayload();
        require(stable.equals(offer.nextReactionValue(0L)),
            "M1-C first offer");
        require(offer.beginProductBatch("PO0200", 1, 10, "L", 50L),
            "M1-C duplicate begin is idempotent");
        require(stable.equals(offer.getStablePayload()) &&
            offer.getOfferCount() == 1,
            "M1-C duplicate does not reset payload or retry count");
        require(!offer.beginProductBatch("PO0200", 1, 5, "L", 60L),
            "M1-C conflicting quantity rejected");
        require(stable.equals(offer.getStablePayload()) &&
            offer.getOfferCount() == 1,
            "M1-C conflict does not mutate identity");
        require(!offer.beginProductBatch("PO0200", 1, 10, "S", 70L),
            "M1-C conflicting size rejected");
        require(stable.equals(offer.getStablePayload()) &&
            offer.getOfferCount() == 1,
            "M1-C size conflict does not mutate identity");
        require(stable.equals(offer.nextReactionValue(199L)) &&
            offer.nextReactionValue(200L) == null,
            "M1-C duplicates/conflicts cannot extend the original hold deadline");
    }

    private static void caseDInvalidOrderIdSkipsTheTrigger() {
        // OrderV1 accepts any non-empty order ID, a space included, but the
        // simulation transport requires printable ASCII without spaces. That
        // mismatch must be reported and skipped, never thrown into the
        // Coordinator clock domain where it would abort order acceptance.
        CoordinatorStateV1.completeOrder();
        require(CoordinatorStateV1.accept("PO 001|1|P1,60,40,2"),
            "M1-D order with a transport-hostile ID is still accepted");
        require(CoordinatorStateV1.requiredBottles == 2 &&
            CoordinatorStateV1.orderActive,
            "M1-D production state is unaffected");
        require(CoordinatorStateV1.currentM4SimulationBatchId() == null &&
            CoordinatorStateV1.currentM4SimulationBatchPayload() == null,
            "M1-D no simulation batch identity is retained");
        require(CoordinatorStateV1.nextM4SimulationBatchRequest() == null,
            "M1-D nothing is published for the skipped batch");
    }

    private static void caseEReplacementAndDiscardCancelHeldCopy() {
        M1SimulationBatchOfferV1 offer = new M1SimulationBatchOfferV1(3, 600L);
        offer.beginProductBatch("PO0300", 1, 1, "S", 0L);
        require("PO0300-P01|1|S".equals(offer.nextReactionValue(0L)),
            "M1-E original copy is active");
        require(offer.beginProductBatch("PO0300", 2, 2, "L", 50L),
            "M1-E next confirmed product replaces pending batch");
        require("PO0300-P02|2|L".equals(offer.nextReactionValue(50L)) &&
            offer.getOfferCount() == 1,
            "M1-E no old payload survives replacement");
        offer.discard();
        require(!offer.isPending() && offer.getStablePayload() == null &&
            offer.getOfferCount() == 0 && offer.nextReactionValue(60L) == null &&
            offer.nextReactionValue(10000L) == null,
            "M1-E discard cancels current hold and every retry");
    }

    private static void caseFHeldCopiesRemainIdempotentAtM4() {
        M1SimulationBatchOfferV1 offer = new M1SimulationBatchOfferV1(3, 600L);
        RecognitionSimulatorStateV1 simulator =
            RecognitionSimulatorStateV1.batchDrivenFromProperties(
                new java.util.Properties(), 0L);
        offer.beginProductBatch("PO0400", 1, 1, "L", 0L);
        // The receiver misses the first reaction, then samples mid-window.
        offer.nextReactionValue(0L);
        require(simulator.startBatchPayload(offer.nextReactionValue(150L), 150L) ==
            RecognitionSimulatorStateV1.BatchStartResult.ACCEPTED,
            "M1-F delayed receiver samples the still-held batch");
        require("PO0400-P01-B001|L".equals(simulator.tick(150L, false)),
            "M1-F exactly one batch-prefixed bottle starts");
        for (long now = 151L; now < 200L; now++) {
            require(simulator.startBatchPayload(offer.nextReactionValue(now), now) ==
                RecognitionSimulatorStateV1.BatchStartResult.DUPLICATE,
                "M1-F every repeated held sample is idempotent");
        }
        simulator.tick(200L, true);
        require(simulator.isFinished() && simulator.distributedCount() == 1,
            "M1-F held delivery generates exactly one bottle");
        offer.nextReactionValue(200L);
        require(simulator.startBatchPayload(offer.nextReactionValue(800L), 800L) ==
            RecognitionSimulatorStateV1.BatchStartResult.DUPLICATE &&
            simulator.tick(800L, false) == null && simulator.distributedCount() == 1,
            "M1-F late retry does not restart a finished batch");
    }

    private static void caseGSystemResetCancelsHeldBatchWithoutInventingAck() {
        CoordinatorStateV1.resetForTest();
        require(CoordinatorStateV1.accept("PO0500|1|P1,S,60,40,1"),
            "M1-G order accepted");
        long now = System.currentTimeMillis();
        require("PO0500-P01|1|S".equals(
            CoordinatorStateV1.nextM4SimulationBatchRequest(now)) &&
            CoordinatorStateV1.m4SimulationBatchTransmissionStarted,
            "M1-G first held batch window starts");
        require("PO0500-P01|1|S".equals(
            CoordinatorStateV1.nextM4SimulationBatchRequest(now + 1L)) &&
            !CoordinatorStateV1.m4SimulationBatchTransmissionStarted &&
            CoordinatorStateV1.lastM4SimulationBatchAttempt == 1,
            "M1-G repeated held reaction is not a new attempt");
        require(CoordinatorStateV1.beginSystemReset("RST0001", now + 10L),
            "M1-G reset accepted during held delivery");
        require(CoordinatorStateV1.nextM4SimulationBatchRequest(now + 20L) == null &&
            CoordinatorStateV1.nextM4SimulationBatchRequest(now + 10000L) == null &&
            CoordinatorStateV1.currentM4SimulationBatchPayload() == null,
            "M1-G reset cancels old batch and retries");
        require(!CoordinatorStateV1.m2SystemResetAcknowledged &&
            !CoordinatorStateV1.m3SystemResetAcknowledged &&
            !CoordinatorStateV1.m4SystemResetAcknowledged,
            "M1-G held transport/reset never fabricates member ACKs");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
