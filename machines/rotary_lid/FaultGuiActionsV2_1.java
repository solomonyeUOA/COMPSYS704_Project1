import java.util.concurrent.atomic.AtomicBoolean;

/** Validated test actions shared by the fault-management user interface. */
public final class FaultGuiActionsV2_1 {
    private static final AtomicBoolean TEST_MODE = new AtomicBoolean(
        Boolean.getBoolean("m3.testMode")
    );

    private FaultGuiActionsV2_1() {
    }

    public static boolean isTestMode() {
        return TEST_MODE.get();
    }

    public static void setTestMode(boolean enabled) {
        TEST_MODE.set(enabled);
    }

    public static boolean perform(String action, String fault) {
        if (!isTestMode() && !"reset".equals(action)) {
            return false;
        }
        if ("inject".equals(action)) {
            return inject(fault);
        }
        if ("safe-stop".equals(action)) {
            return confirmSafeStop();
        }
        if ("controller-evidence".equals(action)) {
            return returnControllerEvidence();
        }
        if ("manual-evidence".equals(action)) {
            return recordManualEvidence();
        }
        if ("resume".equals(action)) {
            return sendResume();
        }
        if ("reset".equals(action)) {
            return SystemWatchdogV1.requestManualSystemReset();
        }
        throw new IllegalArgumentException("unknown action: " + action);
    }

    private static boolean inject(String fault) {
        return FaultInjectionStateV2_1.arm(fault);
    }

    private static boolean confirmSafeStop() {
        return FaultTestControlStateV2_1.requestSafeStop();
    }

    private static boolean returnControllerEvidence() {
        if ("TRANSFER".equals(FaultSupervisorStateV2_1.activeSubsystem())) {
            return FaultTestControlStateV2_1.requestTransferRecovery();
        }
        String state = FaultSupervisorStateV2_1.stateName();
        String event = FaultSupervisorStateV2_1.activeEventId();
        String epoch = FaultSupervisorStateV2_1.activeEpoch();
        long version = FaultSupervisorStateV2_1.activeStateVersion();
        if (("ROTARY".equals(FaultSupervisorStateV2_1.activeSubsystem()) ||
            "LID".equals(FaultSupervisorStateV2_1.activeSubsystem())) &&
            !Member3MachineStateV1.recoverActiveTestFault()) {
            return false;
        }
        if ("WAITING_ACK".equals(state)) {
            int attempt = FaultSupervisorStateV2_1.activeAttempt();
            boolean acknowledged = FaultSupervisorStateV2_1.onRecoveryAck(
                "V2|" + event + "|" + epoch + "|" + attempt +
                "|ACCEPTED|interlocks_satisfied|" + version
            );
            return acknowledged && FaultSupervisorStateV2_1.onRecoveryResult(
                "V2|" + event + "|" + epoch + "|" + attempt +
                "|SUCCESS|" +
                FaultSupervisorStateV2_1.requiredSafeEvidence() + "|" +
                FaultSupervisorStateV2_1.requiredServiceEvidence() + "|" +
                (version + 1)
            );
        }
        if ("RESOURCE_WAIT".equals(state)) {
            return FaultSupervisorStateV2_1.confirmResourceRestored(
                event, true, version + 1
            );
        }
        if ("LOCKED_OUT".equals(state)) {
            return FaultSupervisorStateV2_1.confirmManualControllerEvidence(
                event,
                epoch,
                FaultSupervisorStateV2_1.requiredSafeEvidence(),
                FaultSupervisorStateV2_1.requiredServiceEvidence(),
                version + 1
            );
        }
        return false;
    }

    private static boolean recordManualEvidence() {
        return FaultSupervisorStateV2_1.recordManualEvidence(
            new ManualReconciliationEvidenceV2_1(
                FaultSupervisorStateV2_1.activeEventId(),
                FaultSupervisorStateV2_1.activeEpoch(),
                FaultSupervisorStateV2_1.activeSubsystem(),
                FaultSupervisorStateV2_1.activeBottleId(),
                FaultSupervisorStateV2_1.activeStateVersion(),
                System.getProperty("user.name", "operator"),
                "POSITION_AND_BOTTLE_RECONCILED"
            )
        );
    }

    private static boolean sendResume() {
        return FaultTestControlStateV2_1.requestResume();
    }
}
