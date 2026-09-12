/** Deterministic M2 timing checks runnable at normal or slower demo speeds. */
public final class SlowTransferTimingSelfTest {
    private static int assertions;

    private SlowTransferTimingSelfTest() { }

    public static void main(String[] args) {
        physicalOperationsUseScaledTime();
        conveyorTimeoutRemainsBounded();
        resetCancelsSlowOperationsImmediately();
        System.out.println("SlowTransferTimingSelfTest PASSED (" + assertions +
            " assertions, slowdown=" + SimulationTiming.slowdown() + ")");
    }

    private static void physicalOperationsUseScaledTime() {
        M2PlantStateV1.reset();
        long duration = SimulationTiming.scaleMillis(100L);
        long start = 10000L;
        check(M2PlantStateV1.commandLoad("SLOW-LOAD", start), "loader starts");
        check(M2PlantStateV1.registerConveyorBottle("SLOW-TRANSFER"), "conveyor identity");
        M2PlantStateV1.setConveyorMotor(true, start);
        check(M2PlantStateV1.commandLabel("SLOW-LABEL|LABEL", start), "labeller starts");
        check(M2PlantStateV1.commandUnload("SLOW-UNLOAD", start), "unloader starts");
        tickAll(start + duration - 1L);
        check(M2PlantStateV1.takeLoadConfirmed() == null, "loader not early");
        check(M2PlantStateV1.conveyorFeedback().startsWith("SLOW-TRANSFER|false|"),
            "conveyor arrival not early");
        check(M2PlantStateV1.takeLabelVerification() == null, "label not early");
        check(M2PlantStateV1.takeRemovalConfirmed() == null, "removal not early");
        tickAll(start + duration);
        check("SLOW-LOAD".equals(M2PlantStateV1.takeLoadConfirmed()), "loader on time");
        check(M2PlantStateV1.conveyorFeedback().startsWith("SLOW-TRANSFER|true|"),
            "conveyor arrives on time");
        check("SLOW-LABEL|PASS".equals(M2PlantStateV1.takeLabelVerification()),
            "label evidence on time");
        check("SLOW-UNLOAD|true".equals(M2PlantStateV1.takeRemovalConfirmed()),
            "removal evidence on time");

        M2PlantStateV1.setLabelVerificationFault(true);
        check(M2PlantStateV1.commandLabel("SLOW-FAIL|LABEL", start + duration),
            "faulted label starts");
        M2PlantStateV1.tickLabeller(start + 2L * duration);
        check("SLOW-FAIL|FAIL".equals(M2PlantStateV1.takeLabelVerification()),
            "slow mode preserves verifier failure");
    }

    private static void conveyorTimeoutRemainsBounded() {
        String property = "m2.conveyor.arrivalTimeoutMillis";
        String previous = System.getProperty(property);
        try {
            System.clearProperty(property);
            assertConveyorTimeout(2000L, "SLOW-DEFAULT");
            System.setProperty(property, "350");
            assertConveyorTimeout(350L, "SLOW-CONFIGURED");
        }
        finally {
            if (previous == null) { System.clearProperty(property); }
            else { System.setProperty(property, previous); }
            M2MachineStateV1.reset();
        }
    }

    private static void assertConveyorTimeout(long baseTimeout, String bottle) {
        M2MachineStateV1.reset();
        check(M2MachineStateV1.offerConveyorBottle(bottle + "|S|200|GEOM_S|PACK_S"),
            "conveyor timeout context accepted");
        check(M2MachineStateV1.startConveyor(0L), "conveyor timeout operation starts");
        long deadline = SimulationTiming.scaleMillis(baseTimeout);
        M2MachineStateV1.tickConveyor(deadline - 1L);
        check(M2MachineStateV1.getConveyorStatus() == M2StatusV1.BUSY &&
            M2MachineStateV1.isConveyorMotorEnabled(), "no premature arrival timeout");
        M2MachineStateV1.tickConveyor(deadline);
        check(M2MachineStateV1.getConveyorStatus() == M2StatusV1.FAULT &&
            !M2MachineStateV1.isConveyorMotorEnabled(), "bounded timeout stops motor");
        String fault = M2MachineStateV1.takeConveyorFault();
        check(fault != null && fault.contains("|ARRIVAL_TIMEOUT|"),
            "fault event retains real arrival-timeout cause");
    }

    private static void resetCancelsSlowOperationsImmediately() {
        M2PlantStateV1.reset();
        check(M2PlantStateV1.commandLoad("RESET-LOAD", 0L), "reset loader active");
        check(M2PlantStateV1.registerConveyorBottle("RESET-TRANSFER"), "reset conveyor active");
        M2PlantStateV1.setConveyorMotor(true, 0L);
        check(M2PlantStateV1.commandLabel("RESET-LABEL|LABEL", 0L), "reset label active");
        check(M2PlantStateV1.commandUnload("RESET-UNLOAD", 0L), "reset unload active");
        check(M2SystemResetStateV1.request("RST9001", 1L), "system reset accepted");
        check(M2SystemResetStateV1.isQuarantined() && M2PlantStateV1.isSafeInitialState(),
            "reset stops every plant immediately, without waiting for duration");
        tickAll(SimulationTiming.scaleMillis(100L) + 1000L);
        check(M2PlantStateV1.takeLoadConfirmed() == null &&
            M2PlantStateV1.conveyorFeedback() == null &&
            M2PlantStateV1.takeLabelVerification() == null &&
            M2PlantStateV1.takeRemovalConfirmed() == null,
            "cancelled slow operations never publish late completions");
        M2SystemResetStateV1.tick(2L);
        M2SystemResetStateV1.tick(3L);
        check("RST9001".equals(M2SystemResetStateV1.takeAck(3L)),
            "reset acknowledgement still uses prompt real-time reactions");
    }

    private static void tickAll(long now) {
        M2PlantStateV1.tickLoader(now);
        M2PlantStateV1.tickConveyor(now);
        M2PlantStateV1.tickLabeller(now);
        M2PlantStateV1.tickUnloader(now);
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) { throw new AssertionError(message); }
    }
}
