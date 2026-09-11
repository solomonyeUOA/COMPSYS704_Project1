/** Retained telemetry, causal workpiece ordering and real resource observations. */
public final class Member2LiveTwinSelfTest {
    public static void main(String[] args) {
        testLocalOutboxAndResources();
        testReorderedStages();
        testConfirmedSortAndValidation();
        System.out.println("Member2LiveTwinSelfTest PASSED");
    }

    private static void testLocalOutboxAndResources() {
        M2MachineStateV1.reset();
        DigitalTwinStateV1.reset();
        String bottle = "LIVE-B001";
        String profile = bottle + "|S|200|GEOM_S|PACK_S";
        check(M2MachineStateV1.startLoaderBatch(1), "start batch");
        check(M2MachineStateV1.acceptLoadProfile(profile), "profile creates twin observation");
        check(bottle.equals(M2MachineStateV1.takeLoadCommand(true)), "loader starts");
        check(M2MachineStateV1.confirmLoaded(bottle), "physical load evidence");
        // Intentionally do not consume any SystemJ update signal here.
        String before = snapshot("W:" + bottle);
        check(before.contains("|LOADED|"), "CREATED retained without optional socket pulse");
        String legacy;
        while ((legacy = M2MachineStateV1.takeLoaderWorkpieceUpdate()) != null) {
            check(DigitalTwinStateV1.acceptWorkpieceUpdate(legacy), "duplicate optional pulse accepted");
        }
        check(before.equals(snapshot("W:" + bottle)), "transport duplicate cannot double-advance");
        check(snapshot("R:LOADER-1").contains("|LOAD_CONFIRMED|"), "loader operation visible");
        M2MachineStateV1.nextBottleAtConveyorOffer(0L);
        check(!M2MachineStateV1.startLoaderBatch(1), "held START cannot restart completed q1 loader");
        M2MachineStateV1.observeStartOrderAbsent();
        check(M2MachineStateV1.startLoaderBatch(1), "new ABSENT-rearmed START accepted");
        check(M2MachineStateV1.offerConveyorBottle(profile), "conveyor context");
        M2MachineStateV1.takeConveyorTransferContext();
        check(M2MachineStateV1.startConveyor(0L), "conveyor active");
        check(snapshot("R:CONVEYOR-1").contains("|2|MOVE_TO_P1|"), "conveyor BUSY visible");
        check(M2MachineStateV1.acceptP1Feedback(bottle + "|true|true|true|true|true"), "P1 evidence");
        M2MachineStateV1.nextLoadBottleOffer(0L);
        check(snapshot("R:CONVEYOR-1").contains("|-|1|AWAIT_BOTTLE|"), "conveyor READY after handoff");
        check(M2MachineStateV1.offerBottleAtLabel(bottle), "labeller active");
        M2MachineStateV1.takeLabelCommand();
        check(snapshot("R:LABELLER-1").contains("|2|APPLY_LABEL|"), "label BUSY visible");
        check(M2MachineStateV1.acceptLabelVerification(bottle + "|PASS"), "label PASS");
        M2MachineStateV1.nextMarkLabelledOffer(0L);
        M2MachineStateV1.nextUnloadReadyOffer(0L);
        check(snapshot("R:LABELLER-1").contains("|-|1|AWAIT_BOTTLE|"), "label rearmed state visible");
        check(M2MachineStateV1.acceptUnloadProfile(profile), "unload profile");
        check(M2MachineStateV1.acceptUnloadReady(bottle), "unload permission");
        M2MachineStateV1.takeUnloadCommand();
        check(snapshot("R:UNLOADER-1").contains("|2|REMOVE_FROM_P6|"), "unloader BUSY visible");
        check(M2MachineStateV1.acceptRemovalConfirmed(bottle + "|true", 0L), "removal evidence");
        check(snapshot("R:UNLOADER-1").contains("|3|REMOVAL_CONFIRMED|"), "unloader DONE visible");
        check(DigitalTwinStateV1.getResourceCount() == 4, "all four M2 ResourceTwins exist");
        String viz = DigitalTwinStateV1.nextVisualisationSnapshot(1000L);
        check(viz.contains("|W=1|R=4|") && viz.contains("|RESOURCES="), "workpiece and resources share snapshot");
    }

    private static void testReorderedStages() {
        DigitalTwinStateV1.reset();
        check(observe("E-CAP", "CAPPED", "CAPPER-1", "-", 6L), "cap arrives first");
        check(observe("E-LID", "LIDDED", "LID-1", "-", 5L), "lid arrives early");
        check(observe("E-FILL", "FILLED", "FILLER-AB", "-", 4L), "fill arrives early");
        check(DigitalTwinStateV1.getWorkpieceCount() == 0, "no invented workpiece before context");
        check(observe("E-CREATE", "CREATED", "LOADER-1", "L,500,GEOM_L,PACK_L", 1L), "created context arrives");
        check(snapshot("W:ROUTE-B001").contains("|CREATED|"), "cannot skip loading");
        check(observe("E-P1", "P1", "ROTARY-P1", "-", 3L), "P1 arrives before loaded");
        check(observe("E-LOAD", "LOADED", "LOADER-1", "-", 2L), "loaded arrives last");
        check(snapshot("W:ROUTE-B001").contains("|CAPPED|"), "causal chain drains despite reordering");
        check(DigitalTwinStateV1.getPendingObservationCount() == 0, "reorder queue drained");
        check(snapshot("R:LID-1").contains("|OBSERVED_LIDDED|"), "upstream resource labels its evidence");
    }

    private static void testConfirmedSortAndValidation() {
        check(observe("E-P6", "P6", "LABELLER-1", "-", 7L), "P6");
        check(observe("E-LABEL", "LABELLED", "LABELLER-1", "-", 8L), "labelled");
        check(observe("E-UNLOAD", "UNLOADED", "UNLOADER-1", "-", 9L), "unloaded");
        check(snapshot("W:ROUTE-B001").contains("|UNLOADED|"), "removal alone cannot mean complete");
        check(observe("E-SORT", "SORTED", "SORTPACK-1", "-", 10L), "sort evidence");
        check(snapshot("W:ROUTE-B001").contains("|COMPLETE|"), "sort confirms completion");
        check(snapshot("R:SORTPACK-1").contains("|OBSERVED_SORTED|"), "sortpack resource observation");
        check(!DigitalTwinStateV1.acceptWorkpieceUpdate("garbage"), "malformed event rejected");
        check(!DigitalTwinStateV1.acceptBatchContext("V1|P1|A|999999999999|0|1|S"), "oversized quantity rejected safely");
        check(!observe("BAD", "CREATED", "LOADER-1", "S,500,GEOM_S,PACK_S", 1L), "invalid bottle size context rejected");
        check(observe("LATE", "P1", "ROTARY-P1", "-", 20L), "late event enters validation queue");
        check(snapshot("W:ROUTE-B001").contains("|COMPLETE|"), "late stage cannot regress workpiece");
        check(DigitalTwinStateV1.getPendingObservationCount() == 0, "obsolete stage discarded");
        check(DigitalTwinStateV1.acceptResourceUpdate(M2TwinUpdateV1.resource(
            "ESC-R", "CONVEYOR,2", "CONVEYOR", "-", M2StatusV1.FAULT,
            "STOP;SAFE", "SENSOR=FAIL", 100L)), "punctuation accepted in resource tokens");
        String viz = DigitalTwinStateV1.nextVisualisationSnapshot(2000L);
        check(viz.contains("CONVEYOR%2C2,CONVEYOR,-,4,STOP%3BSAFE,SENSOR%3DFAIL"), "resource wire cells escaped");
        check(viz.contains("ROUTE-B001,COMPLETE") && viz.contains(",L,500"), "bottle size and completion exported");
    }

    private static boolean observe(String event, String stage, String resource, String details, long time) {
        return DigitalTwinStateV1.acceptExternalWorkpieceObservation(M2TwinUpdateV1.workpiece(
            event, "ROUTE-B001", stage, resource, details, time));
    }

    private static String snapshot(String query) {
        DigitalTwinStateV1.requestSnapshot(query);
        return DigitalTwinStateV1.takeSnapshot();
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
