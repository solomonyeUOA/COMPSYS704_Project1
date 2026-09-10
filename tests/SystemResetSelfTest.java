/** Deterministic A-K regression for size-aware orders and system reset. */
public final class SystemResetSelfTest {
    private SystemResetSelfTest() {
    }

    public static void main(String[] args) {
        caseAResetWhileIdle();
        caseBResetDuringActiveOrder();
        caseCDuplicateResetCopies();
        caseDStaleCompletionAfterReset();
        caseEResetDuringPendingOrderTransmission();
        caseFResetDuringPendingCompletionTransmission();
        caseGResetDuringM4Retry();
        caseHNewOrderAfterSuccessfulReset();
        caseISmallBottle();
        caseJLargeBottle();
        caseKMultiProductSmallThenLarge();
        System.out.println("SystemResetSelfTest PASSED (cases A-K)");
    }

    private static void caseAResetWhileIdle() {
        CoordinatorStateV1.resetForTest();
        require(CoordinatorStateV1.beginSystemReset("RST0001", 0L),
            "A idle reset accepted");
        require(CoordinatorStateV1.isSystemResetBlockingOrders(),
            "A reset blocks orders pending external ACKs");
        require("RESET_PENDING_EXTERNAL_ACK".equals(
            CoordinatorStateV1.systemResetState()), "A pending state");
        require("RST0001".equals(
            CoordinatorStateV1.nextM2SystemReset(0L)), "A M2 fan-out");
        require("RST0001".equals(
            CoordinatorStateV1.nextM2SystemReset(1L)),
            "A one reset copy remains PRESENT during its hold window");
        require(CoordinatorStateV1.nextM2SystemReset(500L) == null &&
            CoordinatorStateV1.nextM2SystemReset(749L) == null,
            "A reset copies have an ABSENT retry gap");
        require("RST0001".equals(
            CoordinatorStateV1.nextM2SystemReset(750L)),
            "A retry carries the identical reset identity");
        require("RST0001".equals(
            CoordinatorStateV1.nextM3SystemReset(0L)), "A M3 fan-out");
        require("RST0001".equals(
            CoordinatorStateV1.nextM4SystemReset(0L)), "A M4 fan-out");
        require("RST0001".equals(
            CoordinatorStateV1.nextVisualisationSystemReset(0L)),
            "A visualisation fan-out");
        require(CoordinatorStateV1.nextSystemResetComplete(0L) == null,
            "A completion withheld without ACKs");

        ABSVisualisationFlowModel model = new ABSVisualisationFlowModel();
        model.acceptRequired(3);
        model.acceptStatus(ABSVisualisationFlowModel.LOADER, 2);
        model.resetSystem();
        ABSVisualisationFlowModel.FlowSnapshot snapshot = model.getSnapshot();
        require(snapshot.getRequired() == 0 &&
            snapshot.getRealCompleted() == 0 &&
            snapshot.getVisualCompleted() == 0 &&
            snapshot.getBottles().isEmpty() &&
            snapshot.getModule(ABSVisualisationFlowModel.ROTARY).
                getRotaryAngle() == 0.0,
            "A visualisation model returns to empty initial state");
        ABSVisualisation.resetSystem("RST0001");
    }

    private static void caseBResetDuringActiveOrder() {
        CoordinatorStateV1.resetForTest();
        require(CoordinatorStateV1.accept("PO-B|1|P1,L,60,40,3"),
            "B active order accepted");
        CoordinatorStateV1.loaderStatus = 2;
        require(CoordinatorStateV1.beginSystemReset("RST0002", 10L),
            "B active reset accepted");
        require(!CoordinatorStateV1.orderActive &&
            CoordinatorStateV1.activeOrder == null &&
            CoordinatorStateV1.activeOrderV2 == null,
            "B active order cleared");
        require(CoordinatorStateV1.requiredBottles == 0 &&
            CoordinatorStateV1.completedBottles == 0 &&
            CoordinatorStateV1.loaderStatus == 0,
            "B counts/status clear");
    }

    private static void caseCDuplicateResetCopies() {
        require(!CoordinatorStateV1.beginSystemReset("RST0002", 20L),
            "C duplicate reset does not create another logical reset");
        require("RST0002".equals(CoordinatorStateV1.lastSystemResetId),
            "C stable reset identity");
        int mockCount = MockStateV1.logicalSystemResetCount;
        MockStateV1.requiredBottles = 9;
        MockStateV1.batchActive = true;
        require(MockStateV1.acceptSystemReset("RST9001") &&
            MockStateV1.acceptSystemReset("RST9001") &&
            MockStateV1.acceptSystemReset("RST9001"),
            "C mock ACKs every valid duplicate copy");
        require(MockStateV1.logicalSystemResetCount == mockCount + 1 &&
            MockStateV1.requiredBottles == 0 && !MockStateV1.batchActive,
            "C mock performs one logical reset per identity");

        CoordinatorStateV1.recordSystemResetAck(2, "RST0002", 30L);
        CoordinatorStateV1.recordSystemResetAck(3, "RST0002", 30L);
        CoordinatorStateV1.recordSystemResetAck(4, "RST0002", 30L);
        require(!CoordinatorStateV1.beginSystemReset("RST0002", 40L),
            "C completed reset identity remains de-duplicated");
    }

    private static void caseDStaleCompletionAfterReset() {
        POSVisualisation.resetForTest();
        require(POSVisualisation.queueOrderForTest(
            "PO0001|1|P1,S,60,40,1"), "D POS order queued");
        String sent = POSVisualisation.pollSubmittedOrder();
        POSVisualisation.showSubmitted(sent);
        require(POSVisualisation.beginSystemReset("RST0003", 0L),
            "D POS reset accepted");
        require(POSVisualisation.handleCompletion(
            "PO0001|COMPLETED|1").contains("ignored"),
            "D stale completion ignored");
    }

    private static void caseEResetDuringPendingOrderTransmission() {
        POSVisualisation.resetForTest();
        require(POSVisualisation.queueOrderForTest(
            "PO0001|1|P1,S,60,40,2"), "E pending order queued");
        require(POSVisualisation.beginSystemReset("RST0004", 0L),
            "E reset accepted");
        require(POSVisualisation.pollSubmittedOrder() == null,
            "E pending ORDER copies cancelled");
        require("PO0002".equals(POSVisualisation.nextOrderIdForTest()),
            "E order identity advances rather than being reused");
    }

    private static void caseFResetDuringPendingCompletionTransmission() {
        CoordinatorStateV1.resetForTest();
        require(CoordinatorStateV1.accept("PO-F|1|P1,S,50,50,1"),
            "F order accepted");
        require(CoordinatorStateV1.recordBottleDone(), "F product done");
        CoordinatorStateV1.completeOrder();
        require(CoordinatorStateV1.completionPending,
            "F completion initially pending");
        require(CoordinatorStateV1.beginSystemReset("RST0005", 0L),
            "F reset accepted");
        require(!CoordinatorStateV1.completionPending &&
            CoordinatorStateV1.nextCompletionTransmission() == null,
            "F stale completion transport cleared");
    }

    private static void caseGResetDuringM4Retry() {
        CoordinatorStateV1.resetForTest();
        require(CoordinatorStateV1.accept("PO-G|1|P1,L,60,40,2"),
            "G order accepted");
        require(CoordinatorStateV1.nextM4SimulationBatchRequest() != null,
            "G M4 retry active");
        require(CoordinatorStateV1.beginSystemReset("RST0006", 0L),
            "G reset accepted");
        require(CoordinatorStateV1.currentM4SimulationBatchPayload() == null &&
            CoordinatorStateV1.nextM4SimulationBatchRequest(1000L) == null,
            "G M4 retry discarded");
    }

    private static void caseHNewOrderAfterSuccessfulReset() {
        CoordinatorStateV1.resetForTest();
        require(CoordinatorStateV1.accept("PO-OLD|1|P1,S,60,40,1"),
            "H old order accepted");
        require(CoordinatorStateV1.beginSystemReset("RST0007", 0L),
            "H reset accepted");
        require(!CoordinatorStateV1.accept("PO-BLOCKED|1|P1,S,60,40,1"),
            "H order rejected before ACKs");
        require(CoordinatorStateV1.recordSystemResetAck(2, "RST0007", 10L),
            "H M2 ACK");
        require(CoordinatorStateV1.recordSystemResetAck(3, "RST0007", 10L),
            "H M3 ACK");
        require(CoordinatorStateV1.nextSystemResetComplete(10L) == null,
            "H completion withheld before M4 ACK");
        require(CoordinatorStateV1.recordSystemResetAck(4, "RST0007", 20L),
            "H M4 ACK");
        require("RST0007|RESET_COMPLETE".equals(
            CoordinatorStateV1.nextSystemResetComplete(20L)),
            "H matching completion emitted");
        require(CoordinatorStateV1.accept("PO-NEW|1|P1,S,55,45,2"),
            "H new order accepted after all ACKs");
        require(!CoordinatorStateV1.consumeBottleDoneEdge(true) &&
            CoordinatorStateV1.completedBottles == 0,
            "H stale held BOTTLE_DONE cannot advance new order");
        CoordinatorStateV1.consumeBottleDoneEdge(false);
        require(CoordinatorStateV1.consumeBottleDoneEdge(true),
            "H new event re-arms after ABSENT");
    }

    private static void caseISmallBottle() {
        CoordinatorStateV1.resetForTest();
        require(CoordinatorStateV1.accept("PO-I|1|P1,S,60,40,1"),
            "I S order accepted");
        require("S".equals(CoordinatorStateV1.currentSizeCode()) &&
            CoordinatorStateV1.currentCapacityMl() == 200,
            "I S profile retained");
    }

    private static void caseJLargeBottle() {
        CoordinatorStateV1.resetForTest();
        require(CoordinatorStateV1.accept("PO-J|1|P1,L,60,40,1"),
            "J L order accepted");
        require("L".equals(CoordinatorStateV1.currentSizeCode()) &&
            CoordinatorStateV1.currentCapacityMl() == 500,
            "J L profile retained");
    }

    private static void caseKMultiProductSmallThenLarge() {
        CoordinatorStateV1.resetForTest();
        require(CoordinatorStateV1.accept(
            "PO-K|2|P1,S,60,40,1;P2,L,25,75,2"),
            "K mixed order accepted");
        require("S".equals(CoordinatorStateV1.currentSizeCode()) &&
            "PO-K-P01|1|S".equals(
                CoordinatorStateV1.currentM4SimulationBatchPayload()),
            "K first product is S");
        require(CoordinatorStateV1.recordBottleDone(),
            "K first product complete");
        CoordinatorStateV1.advanceToNextProduct();
        require("P2".equals(CoordinatorStateV1.currentProductId()) &&
            "L".equals(CoordinatorStateV1.currentSizeCode()) &&
            CoordinatorStateV1.currentCapacityMl() == 500 &&
            "PO-K-P02|2|L".equals(
                CoordinatorStateV1.currentM4SimulationBatchPayload()),
            "K second product is L");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
