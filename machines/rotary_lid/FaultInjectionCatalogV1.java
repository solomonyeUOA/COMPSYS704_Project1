/** Representative GUI scenarios; low-level negative tests remain available to self-tests. */
public final class FaultInjectionCatalogV1 {
    private FaultInjectionCatalogV1() { }

    public static String[] codes() {
        return new String[] {"ROTARY_CONTROLLER_FAILURE", "LID_CONTROLLER_FAILURE",
            "ROTARY_DRIVE_FAILURE", "PICK_DRIVE_FAILURE", "PLACE_DRIVE_FAILURE",
            "ROTARY_FEEDBACK_FAILURE", "PICK_TIMEOUT", "ROTARY_BACKUP_FAILURE"};
    }

    // Called outside the injection monitor: never acquire Plant/Machine locks inside it.
    public static String rejection(String code) {
        if (!FaultGuiPolicyV2_1.canInject(FaultSupervisorStateV2_1.stateName()))
            return "Recovery or safe hold is active; no new injection was sent.";
        if (!SystemWatchdogV1.isDriveFailoverEnabled())
            return "Automatic recovery is disabled or in SAFE / ERROR; no injection was sent.";
        FaultMonitoringStateV2_1.ComponentSnapshot[] drives = Member3PlantStateV1.driveMonitoringSnapshot();
        FaultMonitoringStateV2_1.ComponentSnapshot[] controllers = Member3MachineStateV1.controllerMonitoringSnapshot();
        for (FaultMonitoringStateV2_1.ComponentSnapshot[] group :
                new FaultMonitoringStateV2_1.ComponentSnapshot[][] {drives, controllers}) {
            for (FaultMonitoringStateV2_1.ComponentSnapshot item : group) {
                if (!"AVAILABLE".equals(item.state) && !"DEGRADED".equals(item.state))
                    return item.name + ": " + item.state + "; wait for verified completion or inspect safe hold.";
            }
        }
        FaultMonitoringStateV2_1.ComponentSnapshot target =
            "ROTARY_CONTROLLER_FAILURE".equals(code) ? controllers[0] :
            "LID_CONTROLLER_FAILURE".equals(code) ? controllers[1] :
            "ROTARY_DRIVE_FAILURE".equals(code) || "ROTARY_BACKUP_FAILURE".equals(code) ? drives[0] :
            "PICK_DRIVE_FAILURE".equals(code) ? drives[1] :
            "PLACE_DRIVE_FAILURE".equals(code) ? drives[2] :
            "ROTARY_FEEDBACK_FAILURE".equals(code) ? drives[3] : null;
        if (target != null && !"AVAILABLE".equals(target.state))
            return target.name + ": already using backup. Repeating would fail the remaining device. " +
                "Use a fresh test runtime; Reset does not repair failed devices.";
        return null;
    }
}
