/**
 * Runs the real M2 Controller models against the real M3 Rotary Plant and
 * fault-protocol parsers. M4 filling/capping evidence is stubbed explicitly.
 */
public final class Member2Member3SelfTest {
    public static void main(String[] args) {
        testP1ToP6IdentityContract();
        testFaultPayloadCompatibility();
        System.out.println("Member2Member3SelfTest PASSED");
    }

    private static void testP1ToP6IdentityContract() {
        String contextPayload = "B501|S|200|GEOM_S|PACK_S";
        M2BottleContextV1 m2Context =
            M2BottleContextV1.parse(contextPayload);
        BottleContextV1 m3Context = BottleContextV1.parse(contextPayload);
        check(m2Context.encode().equals(m3Context.encode()),
            "M2/M3 full context is byte-identical");

        ConveyorControllerModelV1 conveyor =
            new ConveyorControllerModelV1(1000L);
        check(conveyor.offerBottle(contextPayload), "M2 accepts context");
        conveyor.takeTransferContext();
        check(conveyor.startTransfer(0L), "M2 transfer starts");
        check(conveyor.acceptP1Feedback(
            "B501|true|true|false|true|true"
        ), "P1 photo-eye stops motor");
        check(conveyor.acceptP1Feedback(
            "B501|true|true|true|true|true"
        ), "P1 stable evidence accepted");
        String loadBottle = conveyor.takeLoadBottle();

        RotaryTablePlantModelV1 rotary = new RotaryTablePlantModelV1();
        check(rotary.registerContext(contextPayload),
            "M3 registers same context");
        check(rotary.loadBottle(loadBottle),
            "M3 accepts M2 LOAD_BOTTLE identity");
        rotate(rotary, 1L, 0L);
        check(rotary.markFilled("B501"), "M4 fill stub");
        rotate(rotary, 2L, 1000L);
        check(rotary.markLidPlaced("B501"), "real M3 lid evidence");
        rotate(rotary, 3L, 2000L);
        check(rotary.markCapped("B501"), "M4 cap stub");
        rotate(rotary, 4L, 3000L);
        rotate(rotary, 5L, 4000L);

        LabellerControllerModelV1 labeller =
            new LabellerControllerModelV1();
        check(labeller.offerBottle(rotary.takeLabelOffer()),
            "M2 accepts real M3 BOTTLE_AT_LABEL");
        check("B501|LABEL_B501".equals(labeller.takeLabelCommand()),
            "M2 label command");
        check(labeller.acceptVerification("B501|PASS"),
            "M2 independent label verification");
        check(rotary.markLabelled(labeller.takeMarkLabelled()),
            "M3 accepts M2 MARK_LABELLED");

        BottleUnloaderControllerModelV1 unloader =
            new BottleUnloaderControllerModelV1(500L);
        check(unloader.acceptProfile(contextPayload),
            "M2 Unloader accepts unchanged profile");
        check(unloader.acceptUnloadReady(labeller.takeUnloadReady()),
            "M2 internal label permission");
        check("B501".equals(unloader.takeUnloadCommand()),
            "M2 unloads matching bottle");
        check(unloader.acceptRemovalConfirmed("B501|true", 5000L),
            "physical removal and empty-P6 evidence");
        check(rotary.clearP6(unloader.takeP6Clear()),
            "M3 accepts M2 P6_CLEAR");
        check(contextPayload.equals(unloader.takeSortContext()),
            "M2 preserves context for M4 SortPack");
        check(unloader.isBottleDonePresent(5000L),
            "M2 alone emits bounded BOTTLE_DONE");
    }

    private static void testFaultPayloadCompatibility() {
        M2TransferFaultAdapterModelV2_1 adapter =
            new M2TransferFaultAdapterModelV2_1();
        String event =
            "V2|F501|E11|TRANSFER|ARRIVAL_TIMEOUT|WARNING|B501|7";
        check(adapter.onLocalFault(event), "M2 creates fault event");
        FaultProtocolV2_1.FaultEvent parsedEvent =
            FaultProtocolV2_1.parseFaultEvent(adapter.takeFaultEvent());
        check("F501".equals(parsedEvent.eventId) &&
            parsedEvent.stateVersion == 7L,
            "M3 parses M2 fault event");

        String request = FaultProtocolV2_1.recoveryRequest(
            parsedEvent,
            "RETRY_TRANSFER",
            1
        );
        check(adapter.onRecoveryRequest(request),
            "M2 accepts M3 recovery request");
        FaultProtocolV2_1.RecoveryAck ack =
            FaultProtocolV2_1.parseRecoveryAck(adapter.takeAck());
        check("ACCEPTED".equals(ack.ack) &&
            ack.acceptedStateVersion == 7L,
            "M3 parses M2 ACK");

        String result = "V2|F501|E11|1|SUCCESS|" +
            "motor_off+occupancy_consistent|arrival_confirmed|8";
        check(adapter.onLocalRecoveryEvidence(result),
            "M2 accepts Controller evidence");
        FaultProtocolV2_1.RecoveryResult parsedResult =
            FaultProtocolV2_1.parseRecoveryResult(adapter.takeResult());
        check("SUCCESS".equals(parsedResult.outcome) &&
            parsedResult.resultingStateVersion == 8L,
            "M3 parses M2 result");
    }

    private static void rotate(
        RotaryTablePlantModelV1 rotary,
        long cycleId,
        long startMillis
    ) {
        rotary.setMotorCommand(false, cycleId, startMillis);
        check(rotary.setMotorCommand(true, cycleId, startMillis),
            "rotation starts " + cycleId);
        check(rotary.tick(startMillis + 500L),
            "rotation reaches sensor " + cycleId);
        rotary.setMotorCommand(false, cycleId, startMillis + 500L);
        check(rotary.commitRotation(cycleId),
            "rotation commits " + cycleId);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
