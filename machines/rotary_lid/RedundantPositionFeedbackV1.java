/** Two simulated position channels with independently diagnosed fail-stop health.
 * Both observe the same physical Plant position; neither synthesizes completion.
 */
public final class RedundantPositionFeedbackV1 {
    private final boolean[] failed = new boolean[2];
    private int active;
    private int switches;
    private boolean locked;
    private boolean verifying;
    public void disagree() {
        if (locked) return;
        locked = true;
        DriveEventsV1.record("ROTARY POSITION FEEDBACK", "SAFE_ERROR", switches,
            "Two valid channels disagree; no majority or diagnosed failed channel", true);
    }
    public boolean trusted() { return !locked && !failed[active]; }

    public void failActive() {
        failed[active] = true;
        DriveEventsV1.record("ROTARY POSITION FEEDBACK", "FAULT_DETECTED", switches,
            "Channel " + label() + " diagnosed unavailable", false);
    }

    public boolean sample(boolean physicallyAligned) {
        if (locked) return false;
        if (failed[active]) {
            if (!SystemWatchdogV1.isDriveFailoverEnabled()) return false;
            if (failed[1 - active]) {
                locked = true;
                DriveEventsV1.record("ROTARY POSITION FEEDBACK", "SAFE_ERROR", switches,
                    "Both position channels unavailable", true);
                return false;
            }
            active = 1 - active;
            switches++;
            verifying = true;
            DriveEventsV1.record("ROTARY POSITION FEEDBACK", "SWITCH_TO_" + label(), switches,
                "Healthy standby selected; waiting for physical alignment", false);
        }
        if (physicallyAligned && verifying) {
            verifying = false;
            DriveEventsV1.record("ROTARY POSITION FEEDBACK", "FAILOVER_VERIFIED", switches,
                "Standby sampled actual aligned Plant position", false);
        }
        return physicallyAligned;
    }

    public FaultMonitoringStateV2_1.ComponentSnapshot monitoringSnapshot() {
        return new FaultMonitoringStateV2_1.ComponentSnapshot("ROTARY POSITION FEEDBACK", "M3",
            locked ? "SAFE_ERROR" : failed[active] ? "FEEDBACK_UNAVAILABLE" :
            verifying ? "FAILOVER_VERIFYING" : switches > 0 ? "DEGRADED" : "AVAILABLE",
            "LOCAL FEEDBACK STATE", -1L, -1L,
            "active=" + label() + " | A=" + (failed[0] ? "FAILED" : "OK") +
            " B=" + (failed[1] ? "FAILED" : "OK") + " | switches=" + switches);
    }

    public boolean read(boolean physicallyAligned) {
        return !locked && !failed[active] && physicallyAligned;
    }
    public String visualTelemetry() {
        return label() + "," + failed[0] + "," + failed[1] + "," + monitoringSnapshot().state + "," + switches;
    }

    private String label() { return active == 0 ? "A" : "B"; }
}
