/** Deterministic checks for M2 cross-clock-domain event retention. */
public final class Member2ReliableHandoffSelfTest {
    private static final String SMALL_CONTEXT =
        "B701|S|200|GEOM_S|PACK_S";

    public static void main(String[] args) {
        testBoundedPresentWindows();
        testDifferentClockPhases();
        testLoaderConveyorHandoffAndDeduplication();
        testP6HandoffChain();
        testSequentialQ3P6Handoffs();
        System.out.println("Member2ReliableHandoffSelfTest PASSED");
    }

    private static void testBoundedPresentWindows() {
        M2BoundedSignalOfferV1 offer =
            new M2BoundedSignalOfferV1(3, 500L, 100L);
        check(offer.arm("B700", SMALL_CONTEXT, 0L),
            "handoff arms stable payload");
        check(SMALL_CONTEXT.equals(offer.nextReactionValue(0L)),
            "first PRESENT begins immediately");
        check(SMALL_CONTEXT.equals(offer.nextReactionValue(499L)),
            "payload remains PRESENT for the full window");
        check(offer.nextReactionValue(500L) == null,
            "first window ends with ABSENT");
        check(offer.nextReactionValue(599L) == null,
            "ABSENT gap is retained");
        check(SMALL_CONTEXT.equals(offer.nextReactionValue(600L)),
            "second PRESENT retries identical payload");
        check(offer.nextReactionValue(1100L) == null,
            "second PRESENT has an ABSENT edge");
        check(SMALL_CONTEXT.equals(offer.nextReactionValue(1200L)),
            "third and final PRESENT is emitted");
        check(offer.nextReactionValue(1700L) == null &&
            !offer.isActive(), "retry sequence is bounded");

        check(offer.arm("B702", "B702", 2000L),
            "next logical event can arm after the gap");
        check("B702".equals(offer.nextReactionValue(2000L)),
            "next event becomes PRESENT");
        check(!offer.acknowledge("WRONG"),
            "wrong identity cannot acknowledge an event");
        check(offer.acknowledge("B702"),
            "matching identity cancels remaining retries");
        check(offer.nextReactionValue(2001L) == null,
            "acknowledged event is absent");
    }

    /** Exercises two asynchronous producer/receiver periods at every phase. */
    private static void testDifferentClockPhases() {
        int[] receiverPeriods = {73, 211};
        for (int period : receiverPeriods) {
            for (int phase = 0; phase < period; phase++) {
                M2BoundedSignalOfferV1 offer =
                    new M2BoundedSignalOfferV1(3, 500L, 100L);
                offer.arm("B703", "B703", 0L);
                boolean received = false;
                for (long now = 0L; now <= 1700L; now += 1L) {
                    String value = offer.nextReactionValue(now);
                    if (now >= phase && (now - phase) % period == 0L &&
                        "B703".equals(value)) {
                        received = true;
                        break;
                    }
                }
                check(received, "phase-safe handoff period=" + period +
                    " phase=" + phase);
            }
        }
    }

    private static void testLoaderConveyorHandoffAndDeduplication() {
        M2MachineStateV1.reset();
        check(M2MachineStateV1.startLoaderBatch(1), "start loader batch");
        check(M2MachineStateV1.acceptLoadProfile(SMALL_CONTEXT),
            "accept loader profile");
        check("B701".equals(M2MachineStateV1.takeLoadCommand(true)),
            "loader command");
        check(M2MachineStateV1.confirmLoaded("B701"),
            "physical load confirmation");

        check(SMALL_CONTEXT.equals(
            M2MachineStateV1.nextBottleAtConveyorOffer(0L)),
            "BOTTLE_AT_CONVEYOR retained");
        check(SMALL_CONTEXT.equals(
            M2MachineStateV1.nextBottleAtConveyorOffer(300L)),
            "BOTTLE_AT_CONVEYOR stays PRESENT");
        check(M2MachineStateV1.offerConveyorBottle(SMALL_CONTEXT),
            "conveyor accepts matching context");
        check(M2MachineStateV1.offerConveyorBottle(SMALL_CONTEXT),
            "conveyor de-duplicates repeated copy");
        check(M2MachineStateV1.nextBottleAtConveyorOffer(301L) == null,
            "local receiver acknowledgement cancels retry");

        check("B701".equals(
            M2MachineStateV1.takeConveyorTransferContext()),
            "conveyor plant receives identity once");
        check(M2MachineStateV1.startConveyor(400L),
            "conveyor starts");
        check(M2MachineStateV1.acceptP1Feedback(
            "B701|true|true|true|true|true"),
            "conveyor accepts complete P1 evidence");
        check("B701".equals(M2MachineStateV1.nextLoadBottleOffer(500L)),
            "LOAD_BOTTLE first PRESENT");
        check("B701".equals(M2MachineStateV1.nextLoadBottleOffer(999L)),
            "LOAD_BOTTLE remains observable");
        check(M2MachineStateV1.nextLoadBottleOffer(1000L) == null,
            "LOAD_BOTTLE ABSENT gap");
        check("B701".equals(M2MachineStateV1.nextLoadBottleOffer(1100L)),
            "LOAD_BOTTLE bounded retry");
    }

    private static void testP6HandoffChain() {
        M2MachineStateV1.reset();
        check(M2MachineStateV1.offerBottleAtLabel("B701"),
            "labeller accepts bottle");
        check("B701|LABEL_B701".equals(
            M2MachineStateV1.takeLabelCommand()), "label command");
        check(M2MachineStateV1.acceptLabelVerification("B701|PASS"),
            "label independently verified");

        check("B701".equals(
            M2MachineStateV1.nextMarkLabelledOffer(0L)),
            "MARK_LABELLED retained");
        check("B701".equals(
            M2MachineStateV1.nextUnloadReadyOffer(0L)),
            "UNLOAD_READY retained");
        check(M2MachineStateV1.acceptUnloadReady("B701"),
            "unloader accepts UNLOAD_READY");
        check(M2MachineStateV1.acceptUnloadReady("B701"),
            "unloader de-duplicates UNLOAD_READY retry");
        check(M2MachineStateV1.nextUnloadReadyOffer(1L) == null,
            "UNLOAD_READY acknowledgement cancels retries");

        check(M2MachineStateV1.acceptUnloadProfile(SMALL_CONTEXT),
            "unloader accepts full profile");
        check("B701".equals(M2MachineStateV1.takeUnloadCommand()),
            "matching unload begins");
        check(M2MachineStateV1.acceptRemovalConfirmed(
            "B701|true", 1000L), "P6 physical-clear evidence");
        check("B701".equals(M2MachineStateV1.nextP6ClearOffer(1000L)),
            "P6_CLEAR first PRESENT");
        check(SMALL_CONTEXT.equals(
            M2MachineStateV1.nextBottleReadyForSortOffer(1000L)),
            "BOTTLE_READY_FOR_SORT first PRESENT");
        check("B701".equals(M2MachineStateV1.nextP6ClearOffer(1499L)),
            "P6_CLEAR retained through receiver phase offset");
        check(SMALL_CONTEXT.equals(
            M2MachineStateV1.nextBottleReadyForSortOffer(1499L)),
            "sort context retained unchanged");
        check(M2MachineStateV1.nextP6ClearOffer(1500L) == null,
            "P6_CLEAR ABSENT gap");
        check(M2MachineStateV1.nextBottleReadyForSortOffer(1500L) == null,
            "sort handoff ABSENT gap");
        check("B701".equals(M2MachineStateV1.nextP6ClearOffer(1600L)),
            "P6_CLEAR bounded retry");
        check(SMALL_CONTEXT.equals(
            M2MachineStateV1.nextBottleReadyForSortOffer(1600L)),
            "sort handoff bounded retry");
    }

    private static void testSequentialQ3P6Handoffs() {
        M2MachineStateV1.reset();
        for (int index = 1; index <= 3; index++) {
            String bottleId = "Q3M2B00" + index;
            String context = bottleId + (index % 2 == 1 ?
                "|S|200|GEOM_S|PACK_S" :
                "|L|500|GEOM_L|PACK_L");
            long start = (index - 1L) * 2000L;

            check(M2MachineStateV1.offerBottleAtLabel(bottleId),
                "q3 labeller accepts " + bottleId);
            check(M2MachineStateV1.takeLabelCommand() != null,
                "q3 label command " + bottleId);
            check(M2MachineStateV1.acceptLabelVerification(
                bottleId + "|PASS"), "q3 label verifies " + bottleId);
            check(bottleId.equals(
                M2MachineStateV1.nextMarkLabelledOffer(start)),
                "q3 MARK_LABELLED starts " + bottleId);
            check(bottleId.equals(
                M2MachineStateV1.nextUnloadReadyOffer(start)),
                "q3 UNLOAD_READY starts " + bottleId);
            check(M2MachineStateV1.acceptUnloadReady(bottleId),
                "q3 Unloader accepts " + bottleId);
            check(M2MachineStateV1.acceptUnloadProfile(context),
                "q3 profile accepted " + bottleId);
            check(bottleId.equals(M2MachineStateV1.takeUnloadCommand()),
                "q3 unload command " + bottleId);
            check(M2MachineStateV1.acceptRemovalConfirmed(
                bottleId + "|true", start
            ), "q3 removal confirms " + bottleId);
            check(bottleId.equals(
                M2MachineStateV1.nextP6ClearOffer(start)),
                "q3 P6_CLEAR starts " + bottleId);
            check(context.equals(
                M2MachineStateV1.nextBottleReadyForSortOffer(start)),
                "q3 sort handoff starts " + bottleId);
            check(M2MachineStateV1.isBottleDonePresent(start),
                "q3 BOTTLE_DONE starts " + bottleId);

            exhaustOffer("MARK_LABELLED", bottleId, start);
            exhaustOffer("P6_CLEAR", bottleId, start);
            exhaustOffer("BOTTLE_READY_FOR_SORT", context, start);
            check(!M2MachineStateV1.isBottleDonePresent(start + 501L),
                "q3 BOTTLE_DONE ends " + bottleId);
            check(!M2MachineStateV1.isBottleDonePresent(start + 502L),
                "q3 BOTTLE_DONE ABSENT gap " + bottleId);
        }
    }

    private static void exhaustOffer(
        String signalName,
        String expected,
        long start
    ) {
        check(expected.equals(nextOffer(signalName, start + 499L)),
            signalName + " first window retained");
        check(nextOffer(signalName, start + 500L) == null,
            signalName + " first ABSENT");
        check(expected.equals(nextOffer(signalName, start + 600L)),
            signalName + " second window");
        check(nextOffer(signalName, start + 1100L) == null,
            signalName + " second ABSENT");
        check(expected.equals(nextOffer(signalName, start + 1200L)),
            signalName + " third window");
        check(nextOffer(signalName, start + 1700L) == null,
            signalName + " bounded completion");
    }

    private static String nextOffer(String signalName, long nowMillis) {
        if ("MARK_LABELLED".equals(signalName)) {
            return M2MachineStateV1.nextMarkLabelledOffer(nowMillis);
        }
        if ("P6_CLEAR".equals(signalName)) {
            return M2MachineStateV1.nextP6ClearOffer(nowMillis);
        }
        if ("BOTTLE_READY_FOR_SORT".equals(signalName)) {
            return M2MachineStateV1.nextBottleReadyForSortOffer(nowMillis);
        }
        throw new IllegalArgumentException("unknown signal " + signalName);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
