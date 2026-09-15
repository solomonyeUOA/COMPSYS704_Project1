/** Exercises actual M3 controller replacement, not only the selector. */
public final class ControllerStandbySelfTest {
    public static void main(String[] args) {
        int rounds = args.length == 0 ? 100 : Integer.parseInt(args[0]);
        if (rounds < 1 || rounds > 10000) throw new IllegalArgumentException("rounds must be 1..10000");
        for (int i = 1; i <= rounds; i++) {
            fresh();
            require(Member3MachineStateV1.requestRotation(true), "rotation starts");
            long partial = RotaryControllerModelV1.ROTATION_TIME_MS * i / (rounds + 1);
            Member3MachineStateV1.tickRotary(partial, false);
            long cycle = Member3MachineStateV1.getActiveCycleId();
            require(FaultInjectionStateV2_1.arm("ROTARY_CONTROLLER_FAILURE"), "arm rotary failure");
            Member3MachineStateV1.tickRotary(RotaryControllerModelV1.ROTATION_TIME_MS - partial, false);
            require(!Member3MachineStateV1.takeRotationDoneEvent(), "no synthetic completion");
            Member3MachineStateV1.tickRotary(1, true);
            require(Member3MachineStateV1.getLastCompletedCycleId() == cycle, "original cycle retained");
            require(Member3MachineStateV1.takeRotationDoneEvent(), "completion");
            require(!Member3MachineStateV1.takeRotationDoneEvent(), "no duplicate completion");
            require(Member3MachineStateV1.controllerRedundancySnapshot().contains("active=B"), "backup executes");
            require(FaultInjectionStateV2_1.arm("ROTARY_CONTROLLER_FAILURE"), "fail backup");
            Member3MachineStateV1.tickRotary(1, true);
            require(Member3MachineStateV1.getRotaryStatus() == Member3MachineStateV1.FAULT, "dual failure holds");
            require(!Member3MachineStateV1.isRotaryMotorEnabled(), "output fenced");

            fresh();
            require(Member3MachineStateV1.requestLidLoad("B" + i, true), "lid starts");
            if (i % 2 == 0) Member3MachineStateV1.tickLidLoader(1, true, false);
            require(FaultInjectionStateV2_1.arm("LID_CONTROLLER_FAILURE"), "arm lid failure");
            Member3MachineStateV1.tickLidLoader(1, false, false);
            require(Member3MachineStateV1.takeLidDoneBottleId() == null, "no premature lid completion");
            if (i % 2 != 0) Member3MachineStateV1.tickLidLoader(1, true, false);
            Member3MachineStateV1.tickLidLoader(1, false, true);
            require(("B" + i).equals(Member3MachineStateV1.takeLidDoneBottleId()), "original bottle retained");
            require(Member3MachineStateV1.acknowledgeLidDone(), "ack");
            require(Member3MachineStateV1.takeLidDoneBottleId() == null, "completion consumed");
        }
        ControllerStandbyV1 pair = new ControllerStandbyV1("FENCING TEST");
        pair.checkpoint();
        long old = pair.token();
        pair.failActive();
        require(!pair.owns(old), "failed owner immediately fenced");
        require(!pair.takeover(false), "watchdog OFF cannot switch");
        require(pair.takeover(true) && !pair.owns(old) && pair.owns(pair.token()), "new exclusive owner");
        pair = new ControllerStandbyV1("UNSYNC TEST");
        pair.checkpoint(); pair.invalidateStandby(); pair.failActive();
        for (int i = 0; i < 1000; i++) require(!pair.takeover(true), "unsynced backup locked out");
        fresh();
        require(Member3MachineStateV1.requestRotation(true), "OFF rotation");
        SystemWatchdogV1.setActive(false);
        require(FaultInjectionStateV2_1.arm("ROTARY_CONTROLLER_FAILURE"), "OFF injection");
        Member3MachineStateV1.tickRotary(1, false);
        require(!Member3MachineStateV1.isRotaryMotorEnabled(), "OFF inhibits failed output");
        require(!Member3MachineStateV1.controllerRedundancySnapshot().contains("active=B"), "OFF no takeover");
        for (String action : new String[] {"safe-stop", "manual-evidence", "controller-evidence", "resume"})
            require(!FaultGuiActionsV2_1.perform(action, null), "operator path disabled: " + action);
        require(!FaultTestControlStateV2_1.requestResume(), "no hidden approval path");
        require(!FaultTestControlStateV2_1.requestTransferRecovery(), "no hidden repair path");
        fresh();
        require(Member3MachineStateV1.armTestFault("MOTOR_STALL"), "mechanical fault");
        require(Member3MachineStateV1.requestRotation(true), "mechanical fault triggered");
        require(Member3MachineStateV1.getRotaryStatus() == Member3MachineStateV1.FAULT, "stall not disguised as replaceable drive");
        sustainedBackupProduction(5000);
        System.out.println("ControllerStandbySelfTest PASS: " + (rounds * 2) +
            " takeover cases; 5000 rotary + lid cycles on backup; fencing, OFF, lockout, no operator bypass");
    }

    private static void sustainedBackupProduction(int count) {
        fresh();
        require(FaultInjectionStateV2_1.arm("ROTARY_CONTROLLER_FAILURE"), "arm sustained rotary");
        Member3MachineStateV1.tickRotary(0, false);
        require(FaultInjectionStateV2_1.arm("LID_CONTROLLER_FAILURE"), "arm sustained lid");
        Member3MachineStateV1.tickLidLoader(0, false, false);
        long last = 0;
        java.util.Random random = new java.util.Random(20260915L);
        for (int i = 0; i < count; i++) {
            require(Member3MachineStateV1.requestRotation(true), "backup accepts next cycle " + i);
            long cycle = Member3MachineStateV1.getActiveCycleId();
            require(cycle > last, "cycle identity never reused");
            long duration = RotaryControllerModelV1.ROTATION_TIME_MS;
            long partial = (long) (random.nextDouble() * duration);
            Member3MachineStateV1.tickRotary(partial, false);
            Member3MachineStateV1.tickRotary(duration - partial, false);
            require(!Member3MachineStateV1.takeRotationDoneEvent(), "arrival not invented");
            Member3MachineStateV1.tickRotary(0, true);
            require(Member3MachineStateV1.getLastCompletedCycleId() == cycle, "correct cycle completed");
            require(Member3MachineStateV1.takeRotationDoneEvent() &&
                !Member3MachineStateV1.takeRotationDoneEvent(), "one completion per cycle");
            require(Member3MachineStateV1.acknowledgeRotationDone(), "ack cycle");
            last = cycle;
            String bottle = "SUSTAINED-" + i;
            require(Member3MachineStateV1.requestLidLoad(bottle, true), "backup accepts next bottle");
            Member3MachineStateV1.tickLidLoader(1, true, false);
            require(!Member3MachineStateV1.isLidPickEnabled() && Member3MachineStateV1.isLidPlaceEnabled(),
                "pick and place never execute together");
            Member3MachineStateV1.tickLidLoader(1, false, true);
            require(bottle.equals(Member3MachineStateV1.takeLidDoneBottleId()), "correct bottle completion");
            require(Member3MachineStateV1.acknowledgeLidDone(), "ack bottle");
        }
        require(Member3MachineStateV1.controllerRedundancySnapshot().contains("DEGRADED"), "backup remains active");
        Member3MachineStateV1.systemReset();
        require(Member3MachineStateV1.controllerRedundancySnapshot().contains("A=FAILED"), "reset does not repair primary");
    }
    private static void fresh() {
        Member3MachineStateV1.reset();
        SystemWatchdogV1.resetForTest(System.currentTimeMillis());
        SystemWatchdogV1.setActive(true);
        FaultGuiActionsV2_1.setTestMode(true);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
