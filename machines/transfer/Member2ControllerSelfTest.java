/** Framework-free deterministic checks for the four M2 Controllers. */
public final class Member2ControllerSelfTest {
    public static void main(String[] args) {
        testLoader();
        testConveyor();
        testLabeller();
        testUnloader();
        testConveyorRecovery();
        System.out.println("Member2ControllerSelfTest PASSED");
    }

    private static void testLoader() {
        BottleLoaderControllerModelV1 loader =
            new BottleLoaderControllerModelV1();
        check(loader.getStatus() == M2StatusV1.READY, "loader ready");
        check(loader.startBatch(2), "start two-bottle batch");
        check(loader.acceptProfile("B101|S|200|GEOM_S|PACK_S"),
            "small profile");
        check(loader.acceptProfile("B102|L|500|GEOM_L|PACK_L"),
            "large profile");
        check(loader.takeLoadCommand(false) == null,
            "loader waits for free conveyor entry");
        check("B101".equals(loader.takeLoadCommand(true)),
            "first load command");
        int busy = loader.getStatus();
        check(busy == M2StatusV1.BUSY, "loader busy");
        check(loader.getStatus() == busy, "poll is read-only");
        check(!loader.confirmLoaded("WRONG"), "reject wrong confirmation");
        check(loader.confirmLoaded("B101"), "confirm first bottle");
        check("B101|S|200|GEOM_S|PACK_S".equals(
            loader.takeLoadedContext()), "preserve first context");
        check(loader.getStatus() == M2StatusV1.READY,
            "loader re-arms for remaining bottle");
        check("B102".equals(loader.takeLoadCommand(true)),
            "second load command");
        check(loader.confirmLoaded("B102"), "confirm second bottle");
        check("B102|L|500|GEOM_L|PACK_L".equals(
            loader.takeLoadedContext()), "preserve second context");
        check(loader.isBatchComplete(), "loader batch complete");
        check(loader.getStatus() == M2StatusV1.DONE, "loader done");
        check(!loader.acceptProfile("B101|S|200|GEOM_S|PACK_S"),
            "duplicate profile rejected");
    }

    private static void testConveyor() {
        ConveyorControllerModelV1 conveyor =
            new ConveyorControllerModelV1(1000L);
        check(conveyor.offerBottle("B101|S|200|GEOM_S|PACK_S"),
            "offer bottle to conveyor");
        check("B101".equals(conveyor.takeTransferContext()),
            "plant receives identity");
        check(conveyor.startTransfer(0L), "start conveyor");
        check(conveyor.isMotorEnabled(), "conveyor motor on");
        check(conveyor.acceptP1Feedback(
            "B101|true|true|false|true|true"
        ), "accept P1 arrival snapshot");
        check(!conveyor.isMotorEnabled(), "arrival stops motor");
        check(conveyor.getStatus() == M2StatusV1.BUSY,
            "motor-stopped evidence still required");
        check(conveyor.acceptP1Feedback(
            "B101|true|true|true|true|true"
        ), "accept complete evidence");
        check(conveyor.getStatus() == M2StatusV1.DONE,
            "conveyor done after all evidence");
        check("B101".equals(conveyor.takeLoadBottle()),
            "emit exact LOAD_BOTTLE identity");
        check(conveyor.canAcceptBottle(), "conveyor re-armed");
        check(!conveyor.offerBottle("B101|S|200|GEOM_S|PACK_S"),
            "completed identity cannot be repeated");
    }

    private static void testLabeller() {
        LabellerControllerModelV1 labeller =
            new LabellerControllerModelV1();
        check(labeller.offerBottle("B101"), "offer P6 bottle");
        check("B101|LABEL_B101".equals(labeller.takeLabelCommand()),
            "label command is bottle correlated");
        check(!labeller.acceptVerification("B999|PASS"),
            "reject wrong-bottle verification");
        check(labeller.acceptVerification("B101|PASS"),
            "accept matching verification");
        check("B101".equals(labeller.takeMarkLabelled()),
            "MARK_LABELLED once");
        check("B101".equals(labeller.takeUnloadReady()),
            "UNLOAD_READY once");
        check(labeller.takeMarkLabelled() == null,
            "no duplicate label completion");
        check(!labeller.offerBottle("B101"),
            "completed label is idempotent");
    }

    private static void testUnloader() {
        BottleUnloaderControllerModelV1 unloader =
            new BottleUnloaderControllerModelV1(500L);
        check(unloader.acceptUnloadReady("B101"),
            "accept label permission before profile");
        check(unloader.takeUnloadCommand() == null,
            "do not unload without matching profile");
        check(unloader.acceptProfile("B101|S|200|GEOM_S|PACK_S"),
            "accept matching unload profile");
        check("B101".equals(unloader.takeUnloadCommand()),
            "start matching unload");
        check(!unloader.acceptRemovalConfirmed("B999|true", 1000L),
            "reject wrong removal evidence");
        check(unloader.acceptRemovalConfirmed("B101|true", 1000L),
            "accept physical removal plus empty P6");
        check("B101".equals(unloader.takeP6Clear()), "P6_CLEAR once");
        check("B101|S|200|GEOM_S|PACK_S".equals(
            unloader.takeSortContext()), "forward unchanged sort context");
        check(unloader.isBottleDonePresent(1000L),
            "BOTTLE_DONE begins PRESENT");
        check(unloader.isBottleDonePresent(1499L),
            "BOTTLE_DONE remains observable");
        check(!unloader.isBottleDonePresent(1501L),
            "BOTTLE_DONE becomes ABSENT");
        check(!unloader.isBottleDonePresent(1502L),
            "one ABSENT reaction rearms sender");
        check(unloader.getStatus() == M2StatusV1.READY,
            "unloader re-armed after gap");
        check(!unloader.acceptUnloadReady("B101"),
            "completed bottle cannot be counted twice");
    }

    private static void testConveyorRecovery() {
        ConveyorControllerModelV1 conveyor =
            new ConveyorControllerModelV1(10L);
        check(conveyor.offerBottle("B201|S|200|GEOM_S|PACK_S"),
            "recovery test offer");
        conveyor.takeTransferContext();
        check(conveyor.startTransfer(0L), "recovery test start");
        conveyor.tick(10L, "E07");
        check(conveyor.getStatus() == M2StatusV1.FAULT,
            "arrival timeout faults safely");
        String event = conveyor.takeFaultPayload();
        check(event != null && event.contains("|ARRIVAL_TIMEOUT|"),
            "fault event generated");
        long version = conveyor.getStateVersion();
        String[] eventFields = event.split("\\|", -1);
        String request = "V2|" + eventFields[1] + "|E07|" +
            "RETRY_TRANSFER|1|" + version;
        check(conveyor.acceptRecoveryRequest(request, 20L),
            "one safe retry accepted");
        check(conveyor.acceptP1Feedback(
            "B201|true|true|false|true|true"
        ), "retry arrival");
        check(conveyor.acceptP1Feedback(
            "B201|true|true|true|true|true"
        ), "retry evidence");
        check("B201".equals(conveyor.takeLoadBottle()),
            "retry completes same bottle");
        String evidence = conveyor.takeRecoveryEvidence();
        check(evidence != null && evidence.contains(
            "|SUCCESS|motor_off+occupancy_consistent|arrival_confirmed|"
        ), "independent recovery evidence returned");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
