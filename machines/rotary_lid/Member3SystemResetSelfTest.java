/** Deterministic whole-runtime reset, stale-message and observation regressions. */
public final class Member3SystemResetSelfTest {
    private static int assertions;

    public static void main(String[] args) {
        testRotationAndResetIdentity();
        testBoundedAckWithoutRepeatedRequest();
        testLidInventoryAndActiveActions();
        testPendingOffersAndNewBottle();
        testFaultHistoryAndGuiSequence();
        testUnseenRetiredEpochEvidence();
        testObservationIdentityAndReset();
        System.out.println("Member3SystemResetSelfTest PASSED assertions=" + assertions);
    }

    private static void fresh() {
        Member3MachineStateV1.reset();
        Member3PlantStateV1.reset();
    }

    private static void testRotationAndResetIdentity() {
        fresh();
        require(!M3SystemResetStateV1.request("RST1", 0), "short reset rejected");
        require(!M3SystemResetStateV1.request("RST0001|ACK", 0), "suffix rejected");
        load("OLD");
        require(Member3MachineStateV1.requestRotation(true), "old rotation starts");
        long oldCycle = Member3MachineStateV1.getActiveCycleId();
        Member3PlantStateV1.setRotaryMotor(true, oldCycle, 0);
        require(!Member3PlantStateV1.isSystemResetSafe(), "plant actually moving");
        require(M3SystemResetStateV1.request("RST0001", 100), "reset accepted");
        require(M3SystemResetStateV1.isQuarantined(), "reset gate active");
        require(!Member3MachineStateV1.isRotaryMotorEnabled(), "controller motor off immediately");
        require(Member3PlantStateV1.isSystemResetSafe(), "plant motor stopped before clear");
        require(Member3PlantStateV1.rotarySnapshot().contains("OLD"), "workpiece remains until reconciliation");
        require(M3SystemResetStateV1.takeAck() == null, "no early ACK");
        require(!Member3PlantStateV1.registerBottleContext(context("DURING")), "context quarantined");
        require(!Member3PlantStateV1.loadBottle("DURING"), "load quarantined");
        require(!Member3MachineStateV1.requestLidLoad("DURING", true), "lid quarantined");
        require(!Member3MachineStateV1.requestRotation(true), "rotation quarantined");
        String lateFault = "V2|LATE|A|TRANSFER|ARRIVAL_TIMEOUT|WARNING|DURING|4";
        require(!FaultSupervisorStateV2_1.onTransferFault(lateFault), "late fault quarantined");
        require(M3SystemResetStateV1.request("RST0001", 200), "inflight duplicate accepted");
        M3SystemResetStateV1.tick(849);
        require(M3SystemResetStateV1.takeAck() == null, "drain must finish before ACK");
        M3SystemResetStateV1.tick(850);
        require("RST0001".equals(M3SystemResetStateV1.takeAck()), "matching ACK after safety");
        require(M3SystemResetStateV1.reconciliation().contains("count=1"), "removed bottle audited");
        require(!Member3PlantStateV1.rotarySnapshot().contains("OLD"), "plant emptied");
        require(!Member3PlantStateV1.registerBottleContext(context("OLD")), "old context retired");
        require(!Member3PlantStateV1.loadBottle("OLD"), "old load retired");
        require(!Member3PlantStateV1.commitRotation(oldCycle), "old commit retired");
        require(!Member3PlantStateV1.registerBottleContext(context("DURING")), "discarded context remains retired");
        require(!Member3PlantStateV1.loadBottle("DURING"), "discarded load remains retired");
        require(!Member3MachineStateV1.requestLidLoad("DURING", true), "discarded lid remains retired");
        require(!FaultSupervisorStateV2_1.onTransferFault(lateFault), "discarded fault remains retired");
        load("NEW");
        require(M3SystemResetStateV1.request("RST0001", 900), "completed duplicate accepted");
        require("RST0001".equals(M3SystemResetStateV1.takeAck()), "completed duplicate re-ACKs");
        require(M3SystemResetStateV1.executions() == 1, "duplicate does not reset twice");
        require(Member3PlantStateV1.rotarySnapshot().contains("NEW"), "duplicate keeps new work");
        require(!M3SystemResetStateV1.request("RST00001", 900), "alternate encoding conflicts");
        rotate(1000);
        require(Member3MachineStateV1.getLastCompletedCycleId() > oldCycle, "cycle remains monotonic");
        reset("RST0002", 2000);
        require(!M3SystemResetStateV1.request("RST0001", 3000), "older reset rejected");
        String ack = M3SystemResetStateV1.takeAck();
        require(ack == null || "RST0002".equals(ack), "old reset cannot replace current ACK");
    }

    private static void testBoundedAckWithoutRepeatedRequest() {
        fresh();
        require(M3SystemResetStateV1.request("RST0001", 0), "single reset pulse accepted");
        require(M3SystemResetStateV1.takeAck(749) == null, "no ACK before reset completes");
        M3SystemResetStateV1.tick(750);
        for (int copy = 0; copy < 10; copy++) {
            long start = 750 + copy * 200;
            require("RST0001".equals(M3SystemResetStateV1.takeAck(start)), "ACK window starts without another request");
            require("RST0001".equals(M3SystemResetStateV1.takeAck(start + 99)), "ACK held for slow receiver");
            require(M3SystemResetStateV1.takeAck(start + 100) == null, "ABSENT between ACK copies");
        }
        require(M3SystemResetStateV1.takeAck(10000) == null, "ACK attempts bounded");
        require(M3SystemResetStateV1.executions() == 1, "ACK retries do not rerun reset");
        require(M3SystemResetStateV1.request("RST0001", 10001), "late duplicate can request fresh ACK window");
        require("RST0001".equals(M3SystemResetStateV1.takeAck(10001)), "fresh ACK uses identical ID");
        require(M3SystemResetStateV1.executions() == 1, "late duplicate still non-destructive");
    }

    private static void testLidInventoryAndActiveActions() {
        for (int phase = 0; phase < 3; phase++) {
            fresh();
            int capacity = Member3PlantStateV1.getLidMagazineCapacity();
            placeLid(0);
            require(Member3PlantStateV1.getLidMagazineCount() == capacity - 1, "one real placement consumes inventory");
            require(Member3MachineStateV1.requestLidLoad("ACTIVE", true), "active lid controller starts");
            Member3PlantStateV1.setPickCommand(false, 700);
            Member3PlantStateV1.setPickCommand(true, 700);
            if (phase > 0) Member3PlantStateV1.updateLidLoader(1000);
            if (phase > 1) {
                Member3MachineStateV1.tickLidLoader(300, true, false);
                Member3PlantStateV1.setPlaceCommand(false, 1000);
                Member3PlantStateV1.setPlaceCommand(true, 1000);
            }
            require(!Member3PlantStateV1.isLidActuatorHome(), "active lid action before reset");
            reset("RST0001", 1100);
            require(Member3PlantStateV1.isLidActuatorHome() && Member3PlantStateV1.isNoLidHeld(), "lid home and empty");
            require(!Member3MachineStateV1.isLidPickEnabled() && !Member3MachineStateV1.isLidPlaceEnabled(), "lid outputs off");
            require(Member3PlantStateV1.getLidMagazineCount() == capacity - 1, "reset does not refill magazine");
            require(Member3PlantStateV1.getLidMagazineCapacity() == capacity, "geometry retained");
            require(!Member3PlantStateV1.isLidPlacedSensorActive(), "pending placement feedback cleared");
            require(Member3MachineStateV1.takeLidDoneBottleId() == null, "done latch cleared");
            require(!Member3MachineStateV1.requestLidLoad("ACTIVE", true), "retired lid work rejected");
            require(Member3MachineStateV1.requestLidLoad("NEXT", true), "new lid work accepted");
        }
    }

    private static void testPendingOffersAndNewBottle() {
        for (int station = 1; station <= 3; station++) {
            fresh();
            load("OFFER");
            rotate(0);
            require(Member3PlantStateV1.nextFillOfferWindow() != null, "fill offer armed");
            if (station >= 2) {
                require(Member3PlantStateV1.markFilled("OFFER"), "fill completion");
                rotate(1000);
                require(Member3PlantStateV1.markLidPlaced("OFFER"), "lid completion");
                rotate(2000);
                require(Member3PlantStateV1.nextCapOfferWindow() != null, "cap offer armed");
            }
            if (station >= 3) {
                require(Member3PlantStateV1.markCapped("OFFER"), "cap completion");
                rotate(3000);
                rotate(4000);
                require(Member3PlantStateV1.nextLabelOfferWindow() != null, "label offer armed");
            }
            reset("RST0001", 6000);
            require(Member3PlantStateV1.nextFillOfferWindow() == null, "fill retry cleared");
            require(Member3PlantStateV1.nextCapOfferWindow() == null, "cap retry cleared");
            require(Member3PlantStateV1.nextLabelOfferWindow() == null, "label retry cleared");
            require(Member3PlantStateV1.nextTwinObservation() == null, "observation retry cleared");
            require(!Member3PlantStateV1.markFilled("OFFER") && !Member3PlantStateV1.markLidPlaced("OFFER") &&
                !Member3PlantStateV1.markCapped("OFFER") && !Member3PlantStateV1.markLabelled("OFFER") &&
                !Member3PlantStateV1.clearP6("OFFER"), "old handoffs cannot advance empty system");
            load("POST");
            rotate(7000);
            require(Member3PlantStateV1.markFilled("POST"), "new fill after reset");
            rotate(8000);
            require(Member3PlantStateV1.markLidPlaced("POST"), "new lid after reset");
            rotate(9000);
            require(Member3PlantStateV1.markCapped("POST"), "new cap after reset");
            rotate(10000);
            rotate(11000);
            require(Member3PlantStateV1.markLabelled("POST"), "new label after reset");
            require(Member3PlantStateV1.clearP6("POST"), "new bottle completes after reset");
        }
    }

    private static void testFaultHistoryAndGuiSequence() {
        fresh();
        String event = "V2|E1|E01|TRANSFER|ARRIVAL_TIMEOUT|WARNING|OLD|4";
        FaultPolicyV2_1 policy = FaultPolicyV2_1.select(FaultProtocolV2_1.parseFaultEvent(event));
        require(FaultSupervisorStateV2_1.onTransferFault(event), "retry incident active");
        FaultSupervisorStateV2_1.observeRotaryFault("ROTARY-OLD", "test");
        FaultSupervisorStateV2_1.observeLidFault("LID-OLD", LidLoaderControllerModelV1.Fault.PICK_TIMEOUT);
        reset("RST0001", 0);
        assertIdle();
        require(!FaultSupervisorStateV2_1.onTransferFault(event), "retired FT event rejected");
        require(!FaultSupervisorStateV2_1.onRecoveryAck("V2|E1|A|1|ACCEPTED|route_clear|4"), "old FT ACK rejected");
        require(!FaultSupervisorStateV2_1.onRecoveryResult("V2|E1|A|1|SUCCESS|motor_off+occupancy_consistent|arrival_confirmed|5"), "old FT result rejected");
        require(!FaultSupervisorStateV2_1.onSafeStopAck("V2|E1|A|SAFE_STOPPED|4"), "old safe stop rejected");
        require(!FaultSupervisorStateV2_1.onResumeDecision("V2|E1|A|RESUME|verified|4"), "old resume rejected");
        FaultSupervisorStateV2_1.observeRotaryFault("ROTARY-OLD", "late");
        FaultSupervisorStateV2_1.observeLidFault("LID-OLD", LidLoaderControllerModelV1.Fault.PICK_TIMEOUT);
        assertIdle();
        require(policy.summary().equals(FaultPolicyV2_1.select(FaultProtocolV2_1.parseFaultEvent(event)).summary()), "policy preserved");
        String critical = "V2|E2|E01R1|TRANSFER|POSITION_CONFLICT|CRITICAL|OLD|5";
        require(FaultSupervisorStateV2_1.onTransferFault(critical), "new fault accepted after reset");
        require(FaultSupervisorStateV2_1.onSafeStopAck("V2|E2|E01R1|SAFE_STOPPED|5"), "lockout established");
        require("LOCKED_OUT".equals(FaultSupervisorStateV2_1.stateName()), "locked out before reset");
        reset("RST0002", 1000);
        assertIdle();
        System.setProperty("m3.testMode", "true");
        require(FaultGuiActionsV2_1.perform("inject", "MAGAZINE_EMPTY"), "GUI still accepts test action");
        String first = FaultSupervisorStateV2_1.activeEventId();
        reset("RST0003", 2000);
        require(FaultGuiActionsV2_1.perform("inject", "MAGAZINE_EMPTY"), "GUI stays usable after reset");
        require(!first.equals(FaultSupervisorStateV2_1.activeEventId()), "GUI event sequence not reset");
        System.clearProperty("m3.testMode");
    }

    private static void testObservationIdentityAndReset() {
        fresh();
        load("TWIN-A");
        require(!Member3PlantStateV1.markLidPlaced("TWIN-A"), "no lid observation at wrong station");
        require(Member3PlantStateV1.nextTwinObservation() == null, "no invented observation");
        rotate(0);
        require(Member3PlantStateV1.markFilled("TWIN-A"), "twin bottle filled");
        rotate(1000);
        require(Member3PlantStateV1.markLidPlaced("TWIN-A"), "observed lid success");
        String old = Member3PlantStateV1.nextTwinObservation();
        require(old != null && old.contains("|TWIN-A|LIDDED|LID-1|-|"), "LIDDED links live resource ID");
        require(old.equals(Member3PlantStateV1.nextTwinObservation()), "copies preserve event identity");
        require(!Member3PlantStateV1.markLidPlaced("TWIN-A"), "duplicate success cannot enqueue another event");
        reset("RST0001", 2000);
        require(Member3PlantStateV1.nextTwinObservation() == null, "old copies canceled");
        load("TWIN-B");
        rotate(3000);
        Member3PlantStateV1.markFilled("TWIN-B");
        rotate(4000);
        Member3PlantStateV1.markLidPlaced("TWIN-B");
        String next = Member3PlantStateV1.nextTwinObservation();
        require(next != null && !old.split("\\|")[2].equals(next.split("\\|")[2]), "observation sequence retained across reset");
    }

    private static void testUnseenRetiredEpochEvidence() {
        fresh();
        reset("RST0001", 0);
        String neverSeen = "V2|UNSEEN|E01|TRANSFER|ARRIVAL_TIMEOUT|WARNING|OLD|100";
        require(!FaultSupervisorStateV2_1.onTransferFault(neverSeen), "unseen old epoch rejected after fault-free reset");
        assertIdle();
        require(FaultSupervisorStateV2_1.onTransferFault(
            "V2|FRESH|E01R1|TRANSFER|ARRIVAL_TIMEOUT|WARNING|NEW|1"), "new M2 source epoch accepted");
        FaultSupervisorStateV2_1.takeFaultAlert();
        FaultSupervisorStateV2_1.takeRecoveryRequest();
        require(!FaultSupervisorStateV2_1.onTransferFault(neverSeen), "late old fault cannot replace fresh recovery");
        require(!FaultSupervisorStateV2_1.onRecoveryAck(
            "V2|UNSEEN|E01|1|ACCEPTED|route_clear|100"), "unknown old-epoch ACK ignored");
        require(!FaultSupervisorStateV2_1.onRecoveryResult(
            "V2|UNSEEN|E01|1|SUCCESS|motor_off+occupancy_consistent|arrival_confirmed|101"), "unknown old-epoch result ignored");
        require("WAITING_ACK".equals(FaultSupervisorStateV2_1.stateName()), "late old evidence does not fail fresh incident");
        require("FRESH".equals(FaultSupervisorStateV2_1.activeEventId()), "fresh identity retained");
        require(FaultSupervisorStateV2_1.takeFaultAlert() == null &&
            FaultSupervisorStateV2_1.takeRecoveryFailed() == null, "no new alerts from stale epoch");
        require(FaultSupervisorStateV2_1.onRecoveryAck(
            "V2|FRESH|E01R1|1|ACCEPTED|route_clear|1"), "current epoch ACK still accepted");
        require(FaultSupervisorStateV2_1.onRecoveryResult(
            "V2|FRESH|E01R1|1|SUCCESS|motor_off+occupancy_consistent|arrival_confirmed|2"), "current recovery succeeds");
        reset("RST0002", 1000);
        require(!FaultSupervisorStateV2_1.onTransferFault(
            "V2|UNSEEN2|E01R1|TRANSFER|ARRIVAL_TIMEOUT|WARNING|OLD|101"), "second retired generation rejected");
        require(FaultSupervisorStateV2_1.onTransferFault(
            "V2|FRESH2|E01R2|TRANSFER|ARRIVAL_TIMEOUT|WARNING|NEW|1"), "second new generation accepted");
        reset("RST0003", 2000);
        require(FaultSupervisorStateV2_1.onTransferFault(
            "V2|GUI-100|GUI-TEST|TRANSFER|ARRIVAL_TIMEOUT|WARNING|B-GUI|100"), "GUI-TEST epoch remains usable");
        reset("RST0004", 3000);
        require(FaultSupervisorStateV2_1.onTransferFault(
            "V2|GUI-101|GUI-TEST|TRANSFER|ARRIVAL_TIMEOUT|WARNING|B-GUI|101"), "GUI-TEST epoch never globally retired");
    }

    private static void assertIdle() {
        require("IDLE".equals(FaultSupervisorStateV2_1.stateName()), "supervisor IDLE");
        require("IDLE".equals(FaultSupervisorStateV2_1.decision()), "decision IDLE");
        require("-".equals(FaultSupervisorStateV2_1.activeEventId()), "no active event");
        require("NONE".equals(FaultSupervisorStateV2_1.policySummary()), "no active policy");
        require(FaultSupervisorStateV2_1.activeAttempt() == 0, "attempt cleared");
        require("NONE".equals(FaultSupervisorStateV2_1.latestEvidence()), "evidence cleared");
        require("ROTARY=READY; LID=READY".equals(FaultSupervisorStateV2_1.localSummary()), "local decisions READY");
        require(FaultSupervisorStateV2_1.takeRecoveryRequest() == null && FaultSupervisorStateV2_1.takeFaultAlert() == null &&
            FaultSupervisorStateV2_1.takeSafeStopRequest() == null && FaultSupervisorStateV2_1.takeRecoveryReady() == null &&
            FaultSupervisorStateV2_1.takeRecoveryFailed() == null, "pending FT outputs cleared");
    }

    private static void placeLid(long now) {
        Member3PlantStateV1.setPickCommand(true, now);
        Member3PlantStateV1.updateLidLoader(now + 300);
        Member3PlantStateV1.setPickCommand(false, now + 300);
        Member3PlantStateV1.setPlaceCommand(true, now + 300);
        Member3PlantStateV1.updateLidLoader(now + 600);
        Member3PlantStateV1.setPlaceCommand(false, now + 600);
    }

    private static void reset(String id, long now) {
        require(M3SystemResetStateV1.request(id, now), "reset accepted " + id);
        require(M3SystemResetStateV1.takeAck() == null, "ACK awaits safety " + id);
        M3SystemResetStateV1.tick(now + 750);
        require(id.equals(M3SystemResetStateV1.takeAck()), "safe matching ACK " + id);
        require(!M3SystemResetStateV1.isQuarantined(), "quarantine released " + id);
    }

    private static void load(String id) {
        require(Member3PlantStateV1.registerBottleContext(context(id)), "context " + id);
        require(Member3PlantStateV1.loadBottle(id), "load " + id);
    }

    private static void rotate(long now) {
        require(Member3PlantStateV1.canRotate(), "station barrier ready");
        require(Member3MachineStateV1.requestRotation(true), "controller rotation request");
        long cycle = Member3MachineStateV1.getActiveCycleId();
        Member3PlantStateV1.setRotaryMotor(false, cycle, now);
        Member3PlantStateV1.setRotaryMotor(true, cycle, now);
        require(Member3PlantStateV1.updateRotary(now + 500), "physical rotation complete");
        Member3MachineStateV1.tickRotary(500, false);
        Member3MachineStateV1.tickRotary(1, true);
        require(Member3PlantStateV1.commitRotation(cycle), "matching physical commit");
        Member3PlantStateV1.setRotaryMotor(false, cycle, now + 500);
        require(Member3MachineStateV1.acknowledgeRotationDone(), "controller done acknowledged");
    }

    private static String context(String id) { return id + "|S|200|GEOM_S|PACK_S"; }
    private static void require(boolean passed, String message) {
        assertions++;
        if (!passed) throw new AssertionError(message);
    }
}
