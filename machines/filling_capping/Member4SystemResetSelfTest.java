import java.util.Properties;

/** Reset safety, retained identity fences, and successful post-reset cycles. */
public final class Member4SystemResetSelfTest {
    private static long lastTwinSequence;
    public static void main(String[] args) {
        testCapperMechanicalSequence();
        testRegistryAndSimulatorLedgers();
        testRuntimeResetAndRestart();
        System.out.println("Member4SystemResetSelfTest PASSED");
    }

    private static void testCapperMechanicalSequence() {
        CapperPlantModelV1 capper = new CapperPlantModelV1(10L);
        String[] actions = {"SET_GEOMETRY|GEOM_S", "CLAMP|-", "LOWER|-",
            "GRIP|-", "TWIST|-"};
        long now = 0L;
        for (String action : actions) {
            require(capper.acceptCommand("MECH-OLD|" + action, now), action);
            capper.tick(now + 10L);
            now += 10L;
        }
        require(capper.isLowered() && capper.isClamped() &&
            capper.isGripping(), "fixture: capper is lowered and gripping");
        capper.beginSystemReset(now);
        require(!capper.isGripping() && capper.isLowered() &&
            capper.isClamped(), "stop grip but retain clamp while lowered");
        require(!capper.isSystemResetSafe(), "no receipt-only safety claim");
        capper.tickSystemReset(now + 9L);
        require(capper.isLowered(), "physical return delay respected");
        capper.tickSystemReset(now + 10L);
        require(capper.isLowered() && capper.isClamped(),
            "home is followed by raise, not premature unclamp");
        capper.tickSystemReset(now + 20L);
        require(!capper.isLowered() && capper.isClamped(),
            "raised evidence precedes unclamp");
        capper.tickSystemReset(now + 30L);
        require(capper.isSystemResetSafe() && capper.takeFeedback() == null,
            "only safe idle with no stale feedback satisfies reset");
        require(capper.systemResetEvidence().equals(
            "GRIP_TWIST_STOPPED,HOME_CONFIRMED,RAISED_CONFIRMED,UNCLAMPED_CONFIRMED"),
            "ordered actuator evidence retained");

        CapperPlantModelV1 midStroke = new CapperPlantModelV1(10L);
        midStroke.acceptCommand("MID|SET_GEOMETRY|GEOM_L", 0L);
        midStroke.tick(10L);
        midStroke.acceptCommand("MID|CLAMP|-", 10L);
        midStroke.tick(20L);
        midStroke.acceptCommand("MID|LOWER|-", 20L);
        midStroke.beginSystemReset(25L);
        require(midStroke.isLowered() && midStroke.isClamped(),
            "partly complete lowering must be conservatively homed");
    }

    private static void testRegistryAndSimulatorLedgers() {
        BottleContextRegistryModelV1 registry = new BottleContextRegistryModelV1();
        registry.acceptRecognition("REG-OLD|S|200");
        registry.resetForSystem();
        require(registry.size() == 0 &&
            registry.acceptRecognition("REG-OLD|S|200") == null,
            "registry clears contexts but retains retired IDs");
        require(registry.acceptRecognition("REG-NEW|L|500") != null,
            "new registry identity accepted");

        RecognitionSimulatorStateV1 sim =
            RecognitionSimulatorStateV1.batchDrivenFromProperties(new Properties(), 0L);
        require(sim.startBatchPayload("BATCH-OLD|3|S", 0L) ==
            RecognitionSimulatorStateV1.BatchStartResult.ACCEPTED, "small batch");
        sim.tick(0L, false);
        sim.cancelForSystemReset();
        require(!sim.isBatchActive() && sim.activeBatchId() == null &&
            sim.batchQuantity() == 0 && sim.distributedCount() == 0 &&
            sim.failureReason() == null && sim.tick(9999L, false) == null,
            "active simulator returns to batch-driven idle");
        require(sim.startBatchPayload("BATCH-OLD|3|S", 100L) ==
            RecognitionSimulatorStateV1.BatchStartResult.DUPLICATE &&
            !sim.isBatchActive(), "old batch stays retired, no revival");
        require(sim.startBatchPayload("BATCH-OLD|3|L", 100L) ==
            RecognitionSimulatorStateV1.BatchStartResult.CONFLICT,
            "old batch size contract remains protected");
        require(!M4ResetFenceV1.accept("BATCH-OLD-B003|S|200"),
            "even unissued old-batch bottles cannot leak after reset");
        require(sim.startBatchPayload("BATCH-NEW|1|L", 100L) ==
            RecognitionSimulatorStateV1.BatchStartResult.ACCEPTED &&
            "BATCH-NEW-B001|L".equals(sim.tick(100L, false)),
            "new large batch uses its own size after reset");
        sim.tick(101L, true);
        require(sim.startBatchPayload("BATCH-OLD|3|S", 102L) ==
            RecognitionSimulatorStateV1.BatchStartResult.DUPLICATE,
            "all completed/retired batch IDs retained, not just last batch");
    }

    private static void testRuntimeResetAndRestart() {
        System.setProperty("m4.plant.shortDelayMs", "0");
        System.setProperty("m4.plant.doseDelayMs", "0");
        System.setProperty("m4.plant.refillDelayMs", "0");
        Member4MachineStateV1.reset();
        Member4PlantStateV1.reset();
        RecognitionSimulatorStateV1.startBatchDriven();
        require(RecognitionSimulatorStateV1.acceptBatchRequest("RUNTIME-OLD|3|S"),
            "runtime old batch");
        Member4MachineStateV1.acceptRecognition("RUNTIME-OLD-B001|S|200");
        Member4MachineStateV1.setFillerARatio(60);
        Member4MachineStateV1.acceptBottleAtFill(
            "RUNTIME-OLD-B001|S|200|GEOM_S|PACK_S");
        Member4PlantStateV1.acceptFillerACommand("RUNTIME-OLD-B001|SET_GEOMETRY|GEOM_S");
        Member4PlantStateV1.acceptFillerBCommand("OLD-B|SET_GEOMETRY|GEOM_L");
        Member4PlantStateV1.tick();
        Member4PlantStateV1.acceptFillerACommand("RUNTIME-OLD-B001|START_DOSE|120");
        Member4PlantStateV1.acceptFillerBCommand("OLD-B|START_DOSE|200");
        Member4PlantStateV1.acceptSortPackCommand("OLD-SORT|SET_LANE|LANE_S");
        Member4PlantStateV1.acceptCapperCommand("OLD-CAP|SET_GEOMETRY|GEOM_S");
        Member4PlantStateV1.tick();
        Member4PlantStateV1.acceptCapperCommand("OLD-CAP|CLAMP|-");
        Member4PlantStateV1.tick();
        Member4PlantStateV1.acceptCapperCommand("OLD-CAP|LOWER|-");
        Member4PlantStateV1.tick();
        Member4PlantStateV1.acceptFillerBCommand("OLD-B|START_REFILL|-");
        require(Member4PlantStateV1.snapshot().contains("injector=true") &&
            Member4PlantStateV1.snapshot().contains("lowered=true"),
            "fixture has unsafe-to-discard active plant state");

        require(!M4SystemResetStateV1.request("RST01", 100L), "invalid reset ID");
        require(M4SystemResetStateV1.request("RST0001", 100L), "reset accepted");
        require(M4SystemResetStateV1.takeAck(100L) == null, "no immediate ACK");
        require(M4ResetFenceV1.isQuarantined() &&
            !Member4MachineStateV1.acceptRecognition("QUARANTINED|S|200"),
            "quarantine blocks work");
        require(!RecognitionSimulatorStateV1.acceptBatchRequest("LATE-BATCH|2|L"),
            "quarantine rejects and retires late simulation requests");
        require(!Member4PlantStateV1.snapshot().contains("injector=true") &&
            !Member4PlantStateV1.snapshot().contains("inlet=true") &&
            !Member4PlantStateV1.snapshot().contains("moving=true"),
            "filler valves and movement de-energised first");
        require(Member4MachineStateV1.takeFillerACommand() == null &&
            Member4MachineStateV1.takeRotaryContext() == null &&
            Member4PlantStateV1.takeFillerAFeedback() == null,
            "old bounded commands, context and feedback suppressed");
        M4SystemResetStateV1.tick(100L);
        M4SystemResetStateV1.tick(101L);
        require(M4SystemResetStateV1.takeAck(101L) == null,
            "ACK withheld until final unclamp confirmation");
        M4SystemResetStateV1.tick(102L);
        require("RST0001".equals(M4SystemResetStateV1.takeAck(102L)) &&
            Member4PlantStateV1.isSystemResetSafe(), "matching safe ACK");
        require(Member4MachineStateV1.contextCount() == 0 &&
            RecognitionSimulatorStateV1.nextRequest() == null,
            "registry empty and simulator idle");
        require(RecognitionSimulatorStateV1.acceptBatchRequest("LATE-BATCH|2|L") &&
            RecognitionSimulatorStateV1.nextRequest() == null &&
            !M4ResetFenceV1.accept("LATE-BATCH-B002|L|500"),
            "quarantined batch retry is idempotent, never a new order");
        require(Member4MachineStateV1.takeLoadProfile() == null &&
            Member4MachineStateV1.takeUnloadProfile() == null &&
            Member4MachineStateV1.takeFillADone() == null &&
            Member4MachineStateV1.takeMarkFilled() == null &&
            Member4MachineStateV1.takeMarkCapped() == null &&
            Member4MachineStateV1.takeSortPackCompletion() == null &&
            Member4MachineStateV1.takeWorkpieceObservation() == null &&
            Member4PlantStateV1.takeCapperFeedback() == null,
            "all pending offers/completions canceled");
        require(!Member4MachineStateV1.acceptRecognition("RUNTIME-OLD-B001|S|200") &&
            !Member4MachineStateV1.acceptBottleAtFill(
                "RUNTIME-OLD-B003|S|200|GEOM_S|PACK_S"),
            "late bottle and context identities cannot revive");
        Member4MachineStateV1.acceptRecognition("POST-RESET|L|500");
        long count = M4SystemResetStateV1.resetCount();
        M4SystemResetStateV1.request("RST0001", 300L);
        require(M4SystemResetStateV1.resetCount() == count &&
            Member4MachineStateV1.contextCount() == 1 &&
            "RST0001".equals(M4SystemResetStateV1.takeAck(300L)),
            "duplicate ACK does not clear subsequent state");
        runNewBottle("AFTER-S", "S", 200);
        runNewBottle("AFTER-L", "L", 500);
        M4SystemResetStateV1.request("RST0002", 400L);
        M4SystemResetStateV1.tick(400L);
        M4SystemResetStateV1.tick(401L);
        M4SystemResetStateV1.tick(402L);
        require(!M4SystemResetStateV1.request("RST0001", 500L),
            "older reset cannot trigger after newer completion");
        require("RST0002".equals(M4SystemResetStateV1.takeAck(500L)),
            "only latest ACK returned");
        runNewBottle("AFTER-SECOND-RESET", "S", 200);
    }

    private static void runNewBottle(String id, String size, int capacity) {
        String context = id + "|" + size + "|" + capacity + "|GEOM_" +
            size + "|PACK_" + size;
        Member4MachineStateV1.setFillerARatio(60);
        Member4MachineStateV1.setFillerBRatio(40);
        require(Member4MachineStateV1.acceptBottleAtFill(context), "new fill " + id);
        boolean filled = false;
        boolean capped = false;
        boolean sorted = false;
        for (int i = 0; i < 1000 && !sorted; i++) {
            // Late pre-reset feedback must not fault or advance this new cycle.
            Member4MachineStateV1.acceptFillerAFeedback("RUNTIME-OLD-B001|DOSE_DONE|999");
            transferCommands();
            Member4PlantStateV1.tick();
            String message = Member4PlantStateV1.takeFillerAFeedback();
            if (message != null) { Member4MachineStateV1.acceptFillerAFeedback(message); }
            message = Member4PlantStateV1.takeFillerBFeedback();
            if (message != null) { Member4MachineStateV1.acceptFillerBFeedback(message); }
            message = Member4PlantStateV1.takeCapperFeedback();
            if (message != null) { Member4MachineStateV1.acceptCapperFeedback(message); }
            message = Member4PlantStateV1.takeSortPackFeedback();
            if (message != null) { Member4MachineStateV1.acceptSortPackFeedback(message); }
            message = Member4MachineStateV1.takeFillADone();
            if (message != null) { Member4MachineStateV1.acceptFillADone(message); }
            message = Member4MachineStateV1.takeMarkFilled();
            if (id.equals(message) && !filled) {
                filled = true;
                Member4MachineStateV1.acceptBottleAtCap(context);
            }
            message = Member4MachineStateV1.takeMarkCapped();
            if (id.equals(message) && !capped) {
                capped = true;
                Member4MachineStateV1.acceptBottleReadyForSort(context);
            }
            message = Member4MachineStateV1.takeSortPackCompletion();
            sorted = message != null && message.startsWith(id + "|");
        }
        require(filled && capped && sorted, "new S/L cycle completes " + id +
            "\n" + Member4MachineStateV1.snapshot());
        String[] stages = {"FILLED", "CAPPED", "SORTED"};
        String[] resources = {"FILLER_B", "CAPPER", "SORT_PACK"};
        long time = System.currentTimeMillis();
        for (int stage = 0; stage < stages.length; stage++) {
            String observation = Member4MachineStateV1.takeWorkpieceObservation(time);
            require(observation != null, "queued completion observation " + stages[stage]);
            String[] fields = observation.split("\\|", -1);
            require(fields.length == 8 && "V1".equals(fields[0]) &&
                "W".equals(fields[1]) && id.equals(fields[3]) &&
                stages[stage].equals(fields[4]) && resources[stage].equals(fields[5]) &&
                "-".equals(fields[6]) && Long.parseLong(fields[7]) > 0L,
                "actual completion preserves bottle/stage/resource in twin payload");
            long sequence = Long.parseLong(fields[2].substring("M4-E01-".length()));
            require(sequence > lastTwinSequence,
                "event IDs remain monotonic across stages, bottles and reset");
            lastTwinSequence = sequence;
            require(Member4MachineStateV1.takeWorkpieceObservation(time + 1L) == null,
                "observation retry has a real absent gap");
            for (int copy = 1; copy < 5; copy++) {
                time += 50L;
                require(observation.equals(Member4MachineStateV1.takeWorkpieceObservation(time)),
                    "transport retries preserve exact event identity and timestamp");
            }
            time += 50L;
        }
        require(Member4MachineStateV1.takeWorkpieceObservation(time) == null,
            "all three observations drain without duplicate logical completion");
    }

    private static void transferCommands() {
        String value = Member4MachineStateV1.takeFillerACommand();
        if (value != null) { Member4PlantStateV1.acceptFillerACommand(value); }
        value = Member4MachineStateV1.takeFillerBCommand();
        if (value != null) { Member4PlantStateV1.acceptFillerBCommand(value); }
        value = Member4MachineStateV1.takeCapperCommand();
        if (value != null) { Member4PlantStateV1.acceptCapperCommand(value); }
        value = Member4MachineStateV1.takeSortPackCommand();
        if (value != null) { Member4PlantStateV1.acceptSortPackCommand(value); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
