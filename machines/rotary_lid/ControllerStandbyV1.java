/** In-process fail-stop redundancy. The caller serializes checkpoints and output access. */
public final class ControllerStandbyV1 {
    private final String name;
    private final boolean[] failed = new boolean[2];
    private int active;
    private boolean synchronizedStandby;
    private boolean locked;
    private boolean verifying;
    private long generation;

    public ControllerStandbyV1(String name) { this.name = name; }
    public void checkpoint() { if (!failed[1 - active]) synchronizedStandby = true; }
    public void failActive() {
        failed[active] = true;
        DriveEventsV1.record(name, "FAULT_DETECTED", (int) generation,
            "Controller stopped; old output owner fenced", false);
    }
    public void invalidateStandby() { synchronizedStandby = false; }
    public boolean available() { return !locked && !failed[active]; }
    public long token() { return generation * 2 + active; }
    public boolean owns(long token) { return available() && token == token(); }
    public boolean needsTakeover() { return failed[active] && !locked; }
    public boolean takeover(boolean enabled) {
        if (!needsTakeover() || !enabled) return false;
        if (failed[1 - active] || !synchronizedStandby) {
            locked = true;
            DriveEventsV1.record(name, "SAFE_ERROR", (int) generation,
                "No synchronized healthy controller; outputs inhibited", true);
            return false;
        }
        active = 1 - active;
        generation++;
        synchronizedStandby = false;
        verifying = true;
        DriveEventsV1.record(name, "SWITCH_TO_" + (active == 0 ? "A" : "B"),
            (int) generation, "Checkpoint restored; awaiting action completion", false);
        return true;
    }
    public void completed() {
        if (!available() || !verifying) return;
        verifying = false;
        DriveEventsV1.record(name, "FAILOVER_VERIFIED", (int) generation,
            "Original action completed; degraded operation", false);
    }
    public String snapshot() {
        return name + " | active=" + (active == 0 ? "A" : "B") +
            " | A=" + (failed[0] ? "FAILED" : "OK") +
            " B=" + (failed[1] ? "FAILED" : "OK") +
            " | " + (!available() ? "SAFE_HOLD" : verifying ? "VERIFYING" :
                generation > 0 ? "DEGRADED" : "SYNCHRONIZED") + " | epoch=" + generation;
    }
    public String telemetry() {
        return (active == 0 ? "A" : "B") + "," + failed[0] + "," + failed[1] + "," +
            (!available() ? "SAFE_ERROR" : verifying ? "FAILOVER_VERIFYING" :
                generation > 0 ? "DEGRADED" : "AVAILABLE") + "," + generation;
    }
}
