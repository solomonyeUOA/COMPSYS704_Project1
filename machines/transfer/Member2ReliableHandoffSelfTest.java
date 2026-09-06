/** Deterministic checks for M2 cross-clock-domain event retention. */
public final class Member2ReliableHandoffSelfTest {
    private static final String SMALL_CONTEXT =
        "B701|S|200|GEOM_S|PACK_S";

    public static void main(String[] args) {
        testBoundedRetryPulses();
        testFastReactionEmissionBound();
        testLostPulseRecovery();
        testLoaderConveyorHandoffAndDeduplication();
        testP6HandoffChain();
        testSequentialQ3P6Handoffs();
        System.out.println("Member2ReliableHandoffSelfTest PASSED");
    }

    /** A fast producer may evaluate thousands of reactions but emits three. */
    private static void testFastReactionEmissionBound() {
        M2BoundedSignalOfferV1 offer =
            new M2BoundedSignalOfferV1(3, 600L);
        check(offer.arm("B704", "B704", 0L), "fast retry test arms");
        int emitted = 0;
        int longestPresentRun = 0;
        int presentRun = 0;
        for (long now = 0L; now <= 1201L; now++) {
            if (offer.nextReactionValue(now) != null) {
                emitted++;
                presentRun++;
                longestPresentRun = Math.max(longestPresentRun, presentRun);
            }
            else {
                presentRun = 0;
            }
        }
        check(emitted == 3, "fast reactions still emit only three pulses");
        check(longestPresentRun == 1,
            "no pulse remains PRESENT across consecutive reactions");
        check(!offer.isActive(), "fast retry sequence finishes");
    }

    private static void testBoundedRetryPulses() {
        M2BoundedSignalOfferV1 offer =
            new M2BoundedSignalOfferV1(3, 600L);
        check(offer.arm("B700", SMALL_CONTEXT, 0L),
            "handoff arms stable payload");
        check(SMALL_CONTEXT.equals(offer.nextReactionValue(0L)),
            "first pulse begins immediately");
        check(offer.nextReactionValue(1L) == null,
            "first pulse is followed by ABSENT");
        check(offer.nextReactionValue(599L) == null,
            "retry interval remains ABSENT");
        check(SMALL_CONTEXT.equals(offer.nextReactionValue(600L)),
            "second pulse retries identical payload");
        check(offer.nextReactionValue(601L) == null,
            "second pulse is followed by ABSENT");
        check(SMALL_CONTEXT.equals(offer.nextReactionValue(1200L)),
            "third and final pulse is emitted");
        check(offer.nextReactionValue(1201L) == null &&
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

    /** Verifies that two lost attempts still leave one bounded retry. */
    private static void testLostPulseRecovery() {
        M2BoundedSignalOfferV1 offer =
            new M2BoundedSignalOfferV1(3, 600L);
        check(offer.arm("B703", "B703", 0L), "retry test arms");
        check("B703".equals(offer.nextReactionValue(0L)),
            "first pulse can be lost");
        check(offer.nextReactionValue(1L) == null,
            "first lost pulse returns to ABSENT");
        check("B703".equals(offer.nextReactionValue(600L)),
            "second pulse can be lost");
        check(offer.nextReactionValue(601L) == null,
            "second lost pulse returns to ABSENT");
        check("B703".equals(offer.nextReactionValue(1200L)),
            "third pulse remains available");
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
            "BOTTLE_AT_CONVEYOR first pulse");
        check(M2MachineStateV1.nextBottleAtConveyorOffer(1L) == null,
            "BOTTLE_AT_CONVEYOR returns to ABSENT");
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
            "LOAD_BOTTLE first pulse");
        check(M2MachineStateV1.nextLoadBottleOffer(501L) == null,
            "LOAD_BOTTLE ABSENT reaction");
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
            "P6_CLEAR first pulse");
        check(SMALL_CONTEXT.equals(
            M2MachineStateV1.nextBottleReadyForSortOffer(1000L)),
            "BOTTLE_READY_FOR_SORT first pulse");
        check(M2MachineStateV1.nextP6ClearOffer(1001L) == null,
            "P6_CLEAR ABSENT reaction");
        check(M2MachineStateV1.nextBottleReadyForSortOffer(1001L) == null,
            "sort handoff ABSENT reaction");
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
        check(nextOffer(signalName, start + 1L) == null,
            signalName + " first pulse returns to ABSENT");
        check(expected.equals(nextOffer(signalName, start + 600L)),
            signalName + " second pulse");
        check(nextOffer(signalName, start + 601L) == null,
            signalName + " second pulse returns to ABSENT");
        check(expected.equals(nextOffer(signalName, start + 1200L)),
            signalName + " third pulse");
        check(nextOffer(signalName, start + 1201L) == null,
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
