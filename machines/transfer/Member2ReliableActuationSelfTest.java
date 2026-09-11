/** Dropped command/feedback reactions must not strand or repeat physical actions. */
public final class Member2ReliableActuationSelfTest {
    private static int assertions;

    public static void main(String[] args) {
        verifyDroppedCopies("LOAD", false);
        verifyDroppedCopies("LABEL", false);
        verifyDroppedCopies("UNLOAD", false);
        verifyDroppedCopies("LABEL", true);
        verifyDelayedVerificationCannotChangeOutcome();
        verifyConveyorDelivery();
        verifyResetCancelsAllPairs();
        System.out.println("Member2ReliableActuationSelfTest PASSED (" +
            assertions + " assertions)");
    }

    private static void verifyDroppedCopies(String kind, boolean failure) {
        M2MachineStateV1.reset();
        M2PlantStateV1.reset();
        String bottle = kind + (failure ? "-FAIL" : "-PASS");
        prepare(kind, bottle);
        M2PlantStateV1.setLabelVerificationFault(failure);
        String expectedCommand = "LABEL".equals(kind) ? bottle + "|LABEL_" + bottle : bottle;
        // Drop the first nine actual wire events; a slow peer still sees copy ten.
        for (int copy = 0; copy < 10; copy++) {
            check(expectedCommand.equals(command(kind, copy * 125L)), "stable command retry " + kind);
            check(expectedCommand.equals(command(kind, copy * 125L + 99L)), "command held PRESENT across reactions");
            check(command(kind, copy * 125L + 100L) == null, "command ABSENT gap");
            check(command(kind, copy * 125L + 124L) == null, "command gap lasts25ms");
            if (copy < 9) {
                tick(kind, copy * 125L);
                check(feedback(kind, copy * 125L) == null, "no physical evidence before command delivery");
            }
        }
        check(command(kind, 2000L) == null, "command retries are bounded");
        check(deliverCommand(kind, expectedCommand, 2100L), "last command copy accepted");
        check(deliverCommand(kind, expectedCommand, 2150L), "active duplicate is idempotent");
        tick(kind, 2199L);
        check(feedback(kind, 2199L) == null, "no early physical completion");
        tick(kind, 2200L);
        String evidence = "LOAD".equals(kind) ? bottle : bottle +
            ("LABEL".equals(kind) ? (failure ? "|FAIL" : "|PASS") : "|true");
        for (int copy = 0; copy < 10; copy++) {
            check(evidence.equals(feedback(kind, 2200L + copy * 125L)), "immutable feedback retry " + kind);
            check(evidence.equals(feedback(kind, 2299L + copy * 125L)), "feedback held PRESENT across reactions");
            check(feedback(kind, 2300L + copy * 125L) == null, "feedback ABSENT gap");
            check(feedback(kind, 2324L + copy * 125L) == null, "feedback gap lasts25ms");
            // Clearing a verifier fault must not turn previously observed FAIL into PASS.
            M2PlantStateV1.setLabelVerificationFault(false);
            check(deliverCommand(kind, expectedCommand, 2350L + copy * 125L),
                "completed command retry is idempotent");
            tick(kind, 2400L + copy * 125L);
        }
        check(feedback(kind, 4000L) == null, "feedback has no unbounded replay or second actuation");
        check(deliverFeedback(kind, evidence, 4000L), "last feedback copy reaches controller");
        check(!deliverFeedback(kind, evidence, 4001L), "duplicate evidence has no second completion");
        if ("LABEL".equals(kind) && failure) {
            check(M2MachineStateV1.getLabellerStatus() == M2StatusV1.FAULT, "FAIL stays FAULT");
            check(M2MachineStateV1.nextMarkLabelledOffer(2000L) == null &&
                M2MachineStateV1.nextUnloadReadyOffer(2000L) == null, "failed verification cannot bypass label");
            check(!M2PlantStateV1.commandLabel(bottle + "|DIFFERENT_LABEL", 2000L),
                "conflicting completed label command rejected");
        }
        else {
            int status = "LOAD".equals(kind) ? M2MachineStateV1.getLoaderStatus() :
                "LABEL".equals(kind) ? M2MachineStateV1.getLabellerStatus() : M2MachineStateV1.getUnloaderStatus();
            check(status == M2StatusV1.DONE, "controller completes only after delivered physical evidence");
        }
    }

    private static void verifyDelayedVerificationCannotChangeOutcome() {
        M2PlantStateV1.reset();
        check(M2PlantStateV1.commandLabel("LABEL-OLD|LABEL_LABEL-OLD", 0L), "older label starts");
        M2PlantStateV1.tickLabeller(100L);
        check("LABEL-OLD|PASS".equals(M2PlantStateV1.nextLabelVerificationOffer(100L)),
            "older feedback occupies retained outbox without acknowledgement");
        M2PlantStateV1.setLabelVerificationFault(true);
        check(M2PlantStateV1.commandLabel("LABEL-DELAYED|LABEL_LABEL-DELAYED", 101L),
            "new bottle labels while older feedback remains queued");
        M2PlantStateV1.tickLabeller(201L);
        M2PlantStateV1.setLabelVerificationFault(false);
        M2PlantStateV1.tickLabeller(202L);
        check(M2PlantStateV1.commandLabel("LABEL-DELAYED|LABEL_LABEL-DELAYED", 203L),
            "duplicate cannot restart physical verification");
        check(M2PlantStateV1.nextLabelVerificationOffer(200L) == null, "older feedback first gap");
        for (int copy = 1; copy < 10; copy++) {
            check("LABEL-OLD|PASS".equals(M2PlantStateV1.nextLabelVerificationOffer(100L + copy * 125L)),
                "older feedback remains immutable while new FAIL waits");
            check(M2PlantStateV1.nextLabelVerificationOffer(200L + copy * 125L) == null, "older feedback gap");
        }
        check("LABEL-DELAYED|FAIL".equals(M2PlantStateV1.nextLabelVerificationOffer(1326L)),
            "FAIL is latched at completion before the verifier fault was cleared");
        check(M2PlantStateV1.commandLabel("LABEL-DELAYED|LABEL_LABEL-DELAYED", 1327L),
            "completed delayed command is idempotent");
        M2PlantStateV1.tickLabeller(1500L);
        check(M2PlantStateV1.takeLabelVerification() == null, "no new physical verification from duplicate");
        M2PlantStateV1.reset();
        check(M2PlantStateV1.nextLabelVerificationOffer(1600L) == null &&
            M2PlantStateV1.takeLabelVerification() == null, "reset cancels latched and retained verification");
    }

    private static void verifyConveyorDelivery() {
        M2MachineStateV1.reset();
        M2PlantStateV1.reset();
        String bottle = "CONVEYOR-LOSS";
        check(M2MachineStateV1.offerConveyorBottle(bottle + "|S|200|GEOM_S|PACK_S"), "conveyor context accepted");
        for (int copy = 0; copy < 10; copy++) {
            check(bottle.equals(M2MachineStateV1.nextConveyorTransferOffer(copy * 125L)), "conveyor retained context");
            check(bottle.equals(M2MachineStateV1.nextConveyorTransferOffer(copy * 125L + 99L)), "context held100ms");
            check(M2MachineStateV1.nextConveyorTransferOffer(copy * 125L + 100L) == null, "context ABSENTgap");
        }
        check(M2PlantStateV1.registerConveyorBottle(bottle), "late context delivery accepted");
        M2PlantStateV1.setConveyorMotor(M2MachineStateV1.isConveyorMotorEnabled(), 1250L);
        check(M2PlantStateV1.registerConveyorBottle(bottle), "active transfer duplicate is idempotent");
        M2PlantStateV1.tickConveyor(1350L);
        check(M2MachineStateV1.acceptP1Feedback(M2PlantStateV1.conveyorFeedback()), "arrival evidence accepted");
        M2PlantStateV1.setConveyorMotor(M2MachineStateV1.isConveyorMotorEnabled(), 1351L);
        check(M2MachineStateV1.acceptP1Feedback(M2PlantStateV1.conveyorFeedback()), "stopped evidence accepted");
        check(M2PlantStateV1.commitConveyorHandoff(bottle), "physical handoff commits");
        check(bottle.equals(M2MachineStateV1.nextLoadBottleOffer(1352L)), "one LOAD_BOTTLE handoff");
        check(M2PlantStateV1.registerConveyorBottle(bottle), "late completed context ignored idempotently");
        M2PlantStateV1.setConveyorMotor(true, 1500L);
        M2PlantStateV1.tickConveyor(1600L);
        check(!M2PlantStateV1.isConveyorMotorEnabled() && M2PlantStateV1.conveyorFeedback() == null,
            "completed context never causes second movement");
        check(M2MachineStateV1.nextConveyorTransferOffer(1800L) == null, "context emission cannot restart old movement");
    }

    private static void verifyResetCancelsAllPairs() {
        M2MachineStateV1.reset();
        M2PlantStateV1.reset();
        String[] kinds = { "LOAD", "LABEL", "UNLOAD" };
        for (String kind : kinds) {
            String bottle = "RESET-" + kind;
            prepare(kind, bottle);
            String command = command(kind, 0L);
            check(command != null && deliverCommand(kind, command, 0L), "reset test arms actuator " + kind);
            tick(kind, 100L);
            check(feedback(kind, 100L) != null, "reset test arms retained sensor evidence " + kind);
        }
        check(M2MachineStateV1.offerConveyorBottle("RESET-CONVEYOR|S|200|GEOM_S|PACK_S"), "reset conveyor context");
        check(M2MachineStateV1.nextConveyorTransferOffer(100L) != null, "reset conveyor offer armed");
        check(M2SystemResetStateV1.request("RST0010", 101L), "reset accepted during retained delivery");
        M2SystemResetStateV1.tick(102L);
        M2SystemResetStateV1.tick(103L);
        check(M2PlantStateV1.isSafeInitialState(), "reset clears active actuators and all feedback offers");
        check(M2MachineStateV1.nextConveyorTransferOffer(200L) == null &&
            !M2PlantStateV1.registerConveyorBottle("RESET-CONVEYOR"), "reset cancels and retires conveyor context");
        for (String kind : kinds) {
            check(command(kind, 200L) == null && feedback(kind, 200L) == null,
                "reset cancels both signal directions for " + kind);
            String bottle = "RESET-" + kind;
            String oldCommand = "LABEL".equals(kind) ? bottle + "|LABEL_" + bottle : bottle;
            check(!deliverCommand(kind, oldCommand, 200L), "late command cannot restart retired bottle");
            String evidence = "LOAD".equals(kind) ? bottle : bottle +
                ("LABEL".equals(kind) ? "|PASS" : "|true");
            check(!deliverFeedback(kind, evidence, 200L), "late feedback cannot complete retired bottle");
        }
    }

    private static void prepare(String kind, String bottle) {
        String context = bottle + "|S|200|GEOM_S|PACK_S";
        if ("LOAD".equals(kind)) {
            check(M2MachineStateV1.startLoaderBatch(1), "start loader batch");
            check(M2MachineStateV1.acceptLoadProfile(context), "load profile");
        }
        else if ("LABEL".equals(kind)) {
            check(M2MachineStateV1.offerBottleAtLabel(bottle), "P6 label admission");
        }
        else {
            check(M2MachineStateV1.acceptUnloadProfile(context), "unload profile");
            check(M2MachineStateV1.acceptUnloadReady(bottle), "verified label handoff");
        }
    }

    private static String command(String kind, long now) {
        if ("LOAD".equals(kind)) { return M2MachineStateV1.nextLoadCommandOffer(true, now); }
        if ("LABEL".equals(kind)) { return M2MachineStateV1.nextLabelCommandOffer(now); }
        return M2MachineStateV1.nextUnloadCommandOffer(now);
    }

    private static String feedback(String kind, long now) {
        if ("LOAD".equals(kind)) { return M2PlantStateV1.nextLoadConfirmationOffer(now); }
        if ("LABEL".equals(kind)) { return M2PlantStateV1.nextLabelVerificationOffer(now); }
        return M2PlantStateV1.nextRemovalConfirmationOffer(now);
    }

    private static void tick(String kind, long now) {
        if ("LOAD".equals(kind)) { M2PlantStateV1.tickLoader(now); }
        else if ("LABEL".equals(kind)) { M2PlantStateV1.tickLabeller(now); }
        else { M2PlantStateV1.tickUnloader(now); }
    }

    private static boolean deliverCommand(String kind, String payload, long now) {
        if ("LOAD".equals(kind)) { return M2PlantStateV1.commandLoad(payload, now); }
        if ("LABEL".equals(kind)) { return M2PlantStateV1.commandLabel(payload, now); }
        return M2PlantStateV1.commandUnload(payload, now);
    }

    private static boolean deliverFeedback(String kind, String payload, long now) {
        if ("LOAD".equals(kind)) { return M2MachineStateV1.confirmLoaded(payload); }
        if ("LABEL".equals(kind)) { return M2MachineStateV1.acceptLabelVerification(payload); }
        return M2MachineStateV1.acceptRemovalConfirmed(payload, now);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) { throw new AssertionError(message); }
    }
}
