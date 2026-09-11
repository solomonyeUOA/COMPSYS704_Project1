import java.util.HashMap;
import java.util.Map;

/** Regression for the labeller DONE latch seen between interactive orders. */
public final class Member2RepeatedOrdersSelfTest {
    private static int assertions;

    public static void main(String[] args) {
        verifyEitherCompletionDrainOrder();
        verifyFiveOrdersWithOverlappingRetries();
        System.out.println("Member2RepeatedOrdersSelfTest PASSED (" +
            assertions + " assertions)");
    }

    private static void verifyEitherCompletionDrainOrder() {
        LabellerControllerModelV1 labeller = new LabellerControllerModelV1();
        for (int index = 1; index <= 10; index++) {
            String bottle = "DRAIN-B" + index;
            check(labeller.offerBottle(bottle), "accept next bottle " + bottle);
            check(!labeller.offerBottle("OTHER"), "busy bottle cannot be replaced");
            check(labeller.takeMarkLabelled() == null &&
                labeller.takeUnloadReady() == null,
                "no handoff before independent label verification");
            check((bottle + "|LABEL_" + bottle).equals(labeller.takeLabelCommand()),
                "one bottle-correlated label command");
            check(!labeller.acceptVerification("OTHER|PASS"),
                "wrong-bottle verification cannot release handoffs");
            check(labeller.acceptVerification(bottle + "|PASS"), "verify label");
            check(!labeller.acceptVerification(bottle + "|PASS"),
                "verification retry cannot duplicate completion");
            boolean unloadFirst = index % 2 == 0;
            check(bottle.equals(unloadFirst ? labeller.takeUnloadReady() :
                labeller.takeMarkLabelled()), "first output retains identity");
            check(labeller.getStatus() == M2StatusV1.DONE,
                "one outstanding output keeps bottle reserved");
            check(!labeller.offerBottle("OTHER"), "no early rearm");
            check((unloadFirst ? labeller.takeUnloadReady() :
                labeller.takeMarkLabelled()) == null, "output drains exactly once");
            check(bottle.equals(unloadFirst ? labeller.takeMarkLabelled() :
                labeller.takeUnloadReady()), "second output retains identity");
            check(labeller.getStatus() == M2StatusV1.READY,
                "both drain orders return labeller to READY");
            check(labeller.takeMarkLabelled() == null &&
                labeller.takeUnloadReady() == null, "no duplicate handoffs");
            check(labeller.offerBottle(bottle) && labeller.takeLabelCommand() == null,
                "late completed bottle retry never actuates again");
        }
    }

    /**
     * Uses the real shared M2 facade and bounded offers, in SystemJ's output
     * call order. UNLOAD_READY is acknowledged locally while MARK_LABELLED
     * still has cross-runtime retry copies. The next bottle can finish
     * labelling before those older copies expire. No reset occurs between
     * five orders, and both size contexts must reach the sort handoff intact.
     */
    private static void verifyFiveOrdersWithOverlappingRetries() {
        M2MachineStateV1.reset();
        Map<String, Integer> marks = new HashMap<String, Integer>();
        Map<String, Integer> sorts = new HashMap<String, Integer>();
        int admitted = 0;
        int removed = 0;
        int doneEdges = 0;
        int readyBeforeMark = 0;
        boolean lastDone = false;
        String lastMarked = null;
        String lastSort = null;
        String inLabelPath = null;
        String inUnloader = null;
        long removalAt = 0L;
        long nextArrivalAt = 0L;
        for (long now = 0L; now <= 60000L; now += 10L) {
            if (inLabelPath == null && admitted < 15 && now >= nextArrivalAt &&
                (admitted % 3 != 0 || removed == 0 || !lastDone)) {
                String bottle = bottleId(admitted);
                if (M2MachineStateV1.offerBottleAtLabel(bottle)) {
                    admitted++;
                    inLabelPath = bottle;
                    check(M2MachineStateV1.acceptUnloadProfile(context(bottle, admitted - 1)),
                        "profile retained for " + bottle);
                    check((bottle + "|LABEL_" + bottle).equals(
                        M2MachineStateV1.takeLabelCommand()), "label before unloading");
                    check(M2MachineStateV1.takeUnloadCommand() == null,
                        "unverified label cannot release unload command");
                    check(M2MachineStateV1.acceptLabelVerification(bottle + "|PASS"),
                        "independent label verification for " + bottle);
                }
            }
            String marked = M2MachineStateV1.nextMarkLabelledOffer(now);
            if (marked != null && !marked.equals(lastMarked)) { increment(marks, marked); }
            lastMarked = marked;
            String ready = M2MachineStateV1.nextUnloadReadyOffer(now);
            if (ready != null) {
                if (!marks.containsKey(ready)) { readyBeforeMark++; }
                check(M2MachineStateV1.acceptUnloadReady(ready), "unloader acknowledges identity");
                check(M2MachineStateV1.acceptUnloadReady(ready), "ready retry is idempotent");
            }
            if (inUnloader == null) {
                String command = M2MachineStateV1.takeUnloadCommand();
                if (command != null) {
                    check(command.equals(inLabelPath), "unload only current verified bottle");
                    inUnloader = command;
                    removalAt = now + 100L;
                }
            }
            if (inUnloader != null && now >= removalAt) {
                check(M2MachineStateV1.acceptRemovalConfirmed(inUnloader + "|true", now),
                    "confirmed physical removal");
                inUnloader = null;
                inLabelPath = null;
                removed++;
                nextArrivalAt = now + 100L;
            }
            M2MachineStateV1.nextP6ClearOffer(now);
            String sort = M2MachineStateV1.nextBottleReadyForSortOffer(now);
            if (sort != null && !sort.equals(lastSort)) { increment(sorts, sort); }
            lastSort = sort;
            boolean done = M2MachineStateV1.isBottleDonePresent(now);
            if (done && !lastDone) { doneEdges++; }
            lastDone = done;
            if (removed == 15 && !done && now > removalAt + 1500L) { break; }
        }
        check(readyBeforeMark > 0,
            "regression actually exercises asymmetric MARK_LABELLED retry backpressure");
        check(admitted == 15 && removed == 15 && doneEdges == 15,
            "five sequential three-bottle orders complete without resetting");
        check(M2MachineStateV1.getLabellerStatus() == M2StatusV1.READY,
            "labeller is ready for sixth order");
        int readyUpdates = 0;
        M2TwinUpdateV1.ResourceUpdate lastResource = null;
        String update;
        while ((update = M2MachineStateV1.takeLabellerResourceUpdate()) != null) {
            lastResource = M2TwinUpdateV1.parseResource(update);
            if (lastResource.status == M2StatusV1.READY) { readyUpdates++; }
        }
        check(readyUpdates == 15 && lastResource != null &&
            lastResource.status == M2StatusV1.READY &&
            "AWAIT_BOTTLE".equals(lastResource.operation),
            "ResourceTwin observes exactly one actual READY rearm per bottle");
        for (int index = 0; index < 15; index++) {
            check(Integer.valueOf(5).equals(marks.get(bottleId(index))),
                "exactly five stable label confirmation windows per bottle");
            check(Integer.valueOf(5).equals(sorts.get(context(bottleId(index), index))),
                "exactly five sort windows preserve small/large context");
        }
    }

    private static String bottleId(int index) {
        return "PO000" + (index / 3 + 1) + "-P01-B00" + (index % 3 + 1);
    }

    private static String context(String bottle, int index) {
        return bottle + (index / 3 % 2 == 0 ? "|S|200|GEOM_S|PACK_S" :
            "|L|500|GEOM_L|PACK_L");
    }

    private static void increment(Map<String, Integer> counts, String value) {
        Integer count = counts.get(value);
        counts.put(value, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) { throw new AssertionError(message); }
    }
}
