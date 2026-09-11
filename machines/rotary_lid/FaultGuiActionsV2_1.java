import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Validated test actions shared by the fault-management user interface. */
public final class FaultGuiActionsV2_1 {
    private static final AtomicLong TEST_EVENT_SEQUENCE = new AtomicLong(1);
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
            FaultSupervisorStateV2_1.reset();
            return true;
        }
        throw new IllegalArgumentException("unknown action: " + action);
    }

    private static boolean inject(String fault) {
        String subsystem;
        String severity;
        if ("ALIGNMENT_TIMEOUT".equals(fault)) {
            subsystem = "ROTARY";
            severity = "WARNING";
        }
        else if ("MOTOR_STALL".equals(fault) ||
            "POSITION_SENSOR_FAILURE".equals(fault)) {
            subsystem = "ROTARY";
            severity = "CRITICAL";
        }
        else if ("MAGAZINE_EMPTY".equals(fault)) {
            subsystem = "LID";
            severity = "RESOURCE";
        }
        else if ("PICK_TIMEOUT".equals(fault)) {
            subsystem = "LID";
            severity = "WARNING";
        }
        else if ("PLACEMENT_TIMEOUT".equals(fault) ||
            "LID_SENSOR_FAULT".equals(fault)) {
            subsystem = "LID";
            severity = "CRITICAL";
        }
        else if ("ARRIVAL_TIMEOUT".equals(fault)) {
            subsystem = "TRANSFER";
            severity = "WARNING";
        }
        else if ("DEPARTURE_TIMEOUT".equals(fault) ||
            "PHOTO_EYE_FAILURE".equals(fault) ||
            "POSITION_CONFLICT".equals(fault)) {
            subsystem = "TRANSFER";
            severity = "CRITICAL";
        }
        else {
            throw new IllegalArgumentException("unknown fault: " + fault);
        }

        long sequence = TEST_EVENT_SEQUENCE.getAndIncrement();
        return FaultSupervisorStateV2_1.onFaultEvent(
            "V2|GUI-" + sequence + "|GUI-TEST|" + subsystem + "|" +
            fault + "|" + severity + "|B-GUI|" + sequence
        );
    }

    private static boolean confirmSafeStop() {
        return FaultSupervisorStateV2_1.onSafeStopAck(
            "V2|" + FaultSupervisorStateV2_1.activeEventId() + "|" +
            FaultSupervisorStateV2_1.activeEpoch() + "|SAFE_STOPPED|" +
            FaultSupervisorStateV2_1.activeStateVersion()
        );
    }

    private static boolean returnControllerEvidence() {
        String state = FaultSupervisorStateV2_1.stateName();
        String event = FaultSupervisorStateV2_1.activeEventId();
        String epoch = FaultSupervisorStateV2_1.activeEpoch();
        long version = FaultSupervisorStateV2_1.activeStateVersion();
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
        return FaultSupervisorStateV2_1.onResumeDecision(
            "V2|" + FaultSupervisorStateV2_1.activeEventId() + "|" +
            FaultSupervisorStateV2_1.activeEpoch() +
            "|RESUME|GUI_TEST_APPROVAL|" +
            FaultSupervisorStateV2_1.latestStateVersion()
        );
    }
}
