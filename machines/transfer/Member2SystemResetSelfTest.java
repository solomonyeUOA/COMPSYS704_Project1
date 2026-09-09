/** Safety, reset identity, retirement and fresh-order admission regressions. */
public final class Member2SystemResetSelfTest {
    public static void main(String[] args) {
        M2MachineStateV1.reset();
        M2PlantStateV1.reset();
        DigitalTwinStateV1.reset();
        check(!M2SystemResetStateV1.request("RST1", 0L), "short ID rejected");
        check(!M2SystemResetStateV1.request("RST0001|ACK", 0L), "altered ID rejected");
        resetAndAck("RST0000", 0L);
        check("1".equals(M2SystemResetStateV1.getGeneration()), "RST0000 distinct from startup");
        check(M2SystemResetStateV1.getResetCount() == 1, "idle reset executes once");

        M2MachineStateV1.observeStartOrderAbsent();
        check(M2MachineStateV1.startLoaderBatch(1), "fresh start window accepted");
        check(M2MachineStateV1.takeLoadCommand(true) == null, "must await fresh profile");
        String profile = "PRE-P01-B001|S|200|GEOM_S|PACK_S";
        check(M2MachineStateV1.acceptLoadProfile(profile), "fresh profile after idle reset");
        String created = M2MachineStateV1.takeLoaderWorkpieceUpdate();
        check("PRE-P01-B001".equals(M2MachineStateV1.takeLoadCommand(true)), "load starts");
        check(M2MachineStateV1.confirmLoaded("PRE-P01-B001"), "load confirmation");
        check(M2MachineStateV1.nextBottleAtConveyorOffer(2000L) != null, "loader offer armed");
        check(M2MachineStateV1.offerConveyorBottle(profile), "conveyor context");
        M2MachineStateV1.takeConveyorTransferContext();
        check(M2MachineStateV1.startConveyor(0L), "conveyor active");
        check(M2PlantStateV1.registerConveyorBottle("PRE-P01-B001"), "plant conveyor identity");
        M2PlantStateV1.setConveyorMotor(true, 0L);
        check(M2PlantStateV1.isConveyorMotorEnabled(), "physical motor on");
        check(M2PlantStateV1.commandLoad("PRE-P01-B002", 0L), "loader plant active");
        check(M2MachineStateV1.offerBottleAtLabel("PRE-P01-B003"), "labeller active");
        check(M2PlantStateV1.commandLabel(M2MachineStateV1.takeLabelCommand(), 0L), "label plant active");
        check(M2PlantStateV1.commandUnload("PRE-P01-B004", 0L), "unloader plant active");
        M2PlantStateV1.tickLoader(500L);
        M2PlantStateV1.tickLabeller(500L);
        M2PlantStateV1.tickUnloader(500L);
        DigitalTwinStateV1.requestSnapshot("*");
        check(DigitalTwinStateV1.getWorkpieceCount() > 0, "twin populated before reset");
        long sequence = M2MachineStateV1.getEventSequence();
        String oldEpoch = M2MachineStateV1.getSourceEpoch();
        resetAndAck("RST0002", 3000L);
        check(M2PlantStateV1.isSafeInitialState() && !M2PlantStateV1.isConveyorMotorEnabled(), "all actuators safe");
        check(M2PlantStateV1.takeLoadConfirmed() == null && M2PlantStateV1.takeLabelVerification() == null &&
            M2PlantStateV1.takeRemovalConfirmed() == null && M2PlantStateV1.conveyorFeedback() == null, "pending plant feedback cleared");
        check(DigitalTwinStateV1.getWorkpieceCount() == 0 && DigitalTwinStateV1.getResourceCount() == 0, "both twin stores cleared");
        check(DigitalTwinStateV1.takeSnapshot() == null, "pending snapshot cleared");
        check(M2MachineStateV1.getEventSequence() >= sequence, "event sequence never rewound");
        check(!oldEpoch.equals(M2MachineStateV1.getSourceEpoch()), "FT epoch advanced");
        check(!M2MachineStateV1.startLoaderBatch(1), "held stale START requires ABSENT");
        check(!M2MachineStateV1.acceptLoadProfile(profile), "retired profile rejected");
        check(!M2MachineStateV1.acceptLoadProfile("PRE-P01-B999|S|200|GEOM_S|PACK_S"), "unseen old batch bottle rejected");
        check(!DigitalTwinStateV1.acceptWorkpieceUpdate(created), "retired CREATED cannot revive twin");
        check(!M2PlantStateV1.commandLoad("PRE-P01-B002", 4000L), "retired actuator command rejected");
        check(!M2MachineStateV1.acceptLabelVerification("PRE-P01-B003|PASS"), "late label verification rejected");
        check(!M2TransferFaultAdapterStateV2_1.onLocalFault("V2|OLD|" + oldEpoch +
            "|TRANSFER|ARRIVAL_TIMEOUT|WARNING|PRE-P01-B001|1"), "retired FT epoch rejected");
        assertNoHandoffs(4000L);

        M2MachineStateV1.observeStartOrderAbsent();
        check(M2MachineStateV1.startLoaderBatch(1), "new START admitted after ABSENT");
        check(M2MachineStateV1.takeLoadCommand(true) == null, "new START still needs fresh profile");
        check(M2MachineStateV1.acceptLoadProfile("POST-P01-B001|L|500|GEOM_L|PACK_L"), "new large profile");
        check("POST-P01-B001".equals(M2MachineStateV1.takeLoadCommand(true)), "fresh new order starts");
        int count = M2SystemResetStateV1.getResetCount();
        check(M2SystemResetStateV1.request("RST0002", 5000L), "duplicate accepted");
        check(M2SystemResetStateV1.getResetCount() == count, "duplicate reset cannot erase new work");
        check(M2MachineStateV1.getLoaderStatus() == M2StatusV1.BUSY, "new work survives duplicate reset");
        check(!M2SystemResetStateV1.request("RST0001", 5000L), "older reset rejected");
        check(!M2SystemResetStateV1.request("RST00002", 5000L), "numeric alias rejected");
        check(!M2SystemResetStateV1.request("RST0002 ", 5000L), "whitespace rejected");

        // Arm all downstream retry windows plus anonymous completion, then cancel them.
        check(M2MachineStateV1.offerBottleAtLabel("POST-P01-B002"), "second label bottle");
        M2MachineStateV1.takeLabelCommand();
        check(M2MachineStateV1.acceptLabelVerification("POST-P01-B002|PASS"), "label PASS");
        check(M2MachineStateV1.nextMarkLabelledOffer(6000L) != null, "label offer armed");
        check(M2MachineStateV1.nextUnloadReadyOffer(6000L) != null, "unload ready armed");
        check(M2MachineStateV1.acceptUnloadProfile("POST-P01-B002|S|200|GEOM_S|PACK_S"), "unload context");
        check(M2MachineStateV1.acceptUnloadReady("POST-P01-B002"), "unload permission");
        M2MachineStateV1.takeUnloadCommand();
        check(M2MachineStateV1.acceptRemovalConfirmed("POST-P01-B002|true", 6000L), "removal evidence");
        check(M2MachineStateV1.nextP6ClearOffer(6000L) != null &&
            M2MachineStateV1.nextBottleReadyForSortOffer(6000L) != null &&
            M2MachineStateV1.isBottleDonePresent(6000L), "P6/sort/done active");
        resetAndAck("RST0003", 6001L);
        assertNoHandoffs(6002L);
        resetAndAck("RST99999999999999999999999999999999", 7000L);
        check("100000000000000000000000000000000".equals(M2SystemResetStateV1.getGeneration()), "arbitrary precision reset generation");
        System.out.println("Member2SystemResetSelfTest PASSED");
    }

    private static void resetAndAck(String id, long now) {
        check(M2SystemResetStateV1.request(id, now), "reset accepted " + id);
        check(M2SystemResetStateV1.isQuarantined(), "quarantine immediately");
        check(M2SystemResetStateV1.takeAck(now) == null, "no receipt-only ACK");
        check(!M2PlantStateV1.commandLoad("QUARANTINE", now), "quarantine blocks action");
        M2SystemResetStateV1.tick(now);
        check(M2SystemResetStateV1.takeAck(now) == null, "settle reaction required");
        M2SystemResetStateV1.tick(now + 1L);
        check(id.equals(M2SystemResetStateV1.takeAck(now + 1L)), "matching safe ACK");
        check(M2SystemResetStateV1.takeAck(now + 2L) == null, "ACK ABSENT gap");
        check(id.equals(M2SystemResetStateV1.takeAck(now + 101L)), "ACK survives one missed pulse");
    }

    private static void assertNoHandoffs(long now) {
        check(M2MachineStateV1.nextBottleAtConveyorOffer(now) == null &&
            M2MachineStateV1.nextLoadBottleOffer(now) == null &&
            M2MachineStateV1.nextMarkLabelledOffer(now) == null &&
            M2MachineStateV1.nextUnloadReadyOffer(now) == null &&
            M2MachineStateV1.nextP6ClearOffer(now) == null &&
            M2MachineStateV1.nextBottleReadyForSortOffer(now) == null &&
            !M2MachineStateV1.isBottleDonePresent(now), "all handoffs/done cancelled");
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
