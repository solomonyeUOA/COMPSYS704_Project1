/** Pure mapping from backend monitoring data to read-only display states. */
public final class FaultMonitoringPresentationV2_1 {
    private FaultMonitoringPresentationV2_1() {
    }

    public static String displayState(
        FaultMonitoringStateV2_1.ComponentSnapshot component,
        FaultMonitoringStateV2_1.Snapshot current
    ) {
        if (FaultMonitoringStateV2_1.SUPERVISOR.equals(component.name)) {
            if ("WAITING_RESULT".equals(current.supervisorState)) return "RECOVERING";
            if ("LOCKED_OUT".equals(current.supervisorState)) return "ISOLATED";
            if ("RECOVERY_READY".equals(current.supervisorState)) return "VERIFIED";
            return current.supervisorState;
        }
        if (isFaultSource(component, current)) {
            if ("WAITING_ACK".equals(current.supervisorState) ||
                "WAITING_RESULT".equals(current.supervisorState)) {
                return current.maximumAttempts > 0 ?
                    "RECOVERING " + current.attempt + "/" +
                        current.maximumAttempts : "RECOVERING";
            }
            if ("RECOVERY_READY".equals(current.supervisorState)) return "VERIFIED";
            if ("WAITING_SAFE_STOP".equals(current.supervisorState) ||
                "LOCKED_OUT".equals(current.supervisorState) ||
                "MANUAL_RECOVERY".equals(current.supervisorState) ||
                "FAILED".equals(current.supervisorState)) {
                return "FAULT / ISOLATED";
            }
        }
        if ("UNRESPONSIVE".equals(component.heartbeat)) return "UNRESPONSIVE";
        return component.state;
    }

    public static boolean isFaultSource(
        FaultMonitoringStateV2_1.ComponentSnapshot component,
        FaultMonitoringStateV2_1.Snapshot current
    ) {
        if (component == null || current == null || "-".equals(current.faultCode)) {
            return false;
        }
        return ("ROTARY".equals(current.subsystem) &&
                FaultMonitoringStateV2_1.ROTARY_CONTROLLER.equals(component.name)) ||
            ("LID".equals(current.subsystem) &&
                FaultMonitoringStateV2_1.LID_CONTROLLER.equals(component.name)) ||
            ("TRANSFER".equals(current.subsystem) &&
                FaultMonitoringStateV2_1.M2_LINK.equals(component.name));
    }
}
