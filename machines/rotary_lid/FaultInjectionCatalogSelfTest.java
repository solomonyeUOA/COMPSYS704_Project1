/** GUI command preflight regression; not a substitute for native GUI interaction. */
public final class FaultInjectionCatalogSelfTest {
    public static void main(String[] args) {
        require(FaultInjectionCatalogV1.codes().length == 8, "representative list");
        for (int round = 0; round < 100; round++) {
            Member3MachineStateV1.reset();
            Member3PlantStateV1.reset();
            FaultSupervisorStateV2_1.reset();
            SystemWatchdogV1.resetForTest(System.currentTimeMillis());
            SystemWatchdogV1.setActive(true);
            FaultGuiActionsV2_1.setTestMode(true);
            require(FaultGuiActionsV2_1.perform("inject", "ROTARY_DRIVE_FAILURE"), "first injection");
            require(FaultGuiActionsV2_1.perform("cancel-injection", ""), "pending cancellation");
            Member3PlantStateV1.injectDriveFailure("ROTARY", 0);
            require(FaultInjectionCatalogV1.rejection("ROTARY_DRIVE_FAILURE") != null, "pending failover blocked");
            Member3PlantStateV1.registerBottleContext("TEST|S|200|GEOM_S|PACK_S");
            Member3PlantStateV1.loadBottle("TEST");
            Member3PlantStateV1.setRotaryMotor(true, 1);
            long now = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            for (long t = now; t < now + 2000; t++) Member3PlantStateV1.updateRotaryAt(t);
            String error = FaultInjectionCatalogV1.rejection("ROTARY_DRIVE_FAILURE");
            require(error != null && error.contains("already using backup"), "repeat cannot kill B");
            try {
                FaultGuiActionsV2_1.perform("inject", "ROTARY_DRIVE_FAILURE");
                throw new AssertionError("repeat accepted");
            } catch (IllegalStateException expected) { }
            require("-".equals(FaultInjectionStateV2_1.armedFault()), "rejection leaves no pending request");
            require(FaultInjectionCatalogV1.rejection("PICK_DRIVE_FAILURE") == null, "other healthy pair testable");
        }
        System.out.println("FaultInjectionCatalogSelfTest PASS: 100 repeat-injection guards and cancellations");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
