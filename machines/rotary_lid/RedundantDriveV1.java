/** M3 simulation: two independently isolatable drives sharing one mechanical axis. */
public final class RedundantDriveV1 {
    private final String name;
    private final boolean[] failed = new boolean[2];
    private final boolean[] isolated = new boolean[2];
    private int active;
    private int switches;
    private boolean locked;
    private boolean verificationPending;
    private String result = "NONE";
    private final DriveMechanismV1 mechanism = new DriveMechanismV1();
    private String phase = "RUN";
    private long phaseAt;
    public static final long PHASE_TIMEOUT_MS = Math.max(10L, SimulationTiming.scaleMillis(150L));
    private static final long VERIFY_TIMEOUT_MS = Math.max(100L, SimulationTiming.scaleMillis(2000L));

    public DriveMechanismV1 mechanism() { return mechanism; }
    public String phase() { return phase; }

    public RedundantDriveV1(String name) { this.name = name; }

    public void fail(int channel, boolean disconnectable) {
        check(channel);
        failed[channel] = true;
        // Legacy argument means the branch is disconnectable, not already isolated.
        if (!disconnectable) mechanism.inject("ISOLATION_FAILURE");
        result = "AWAITING_ACTION";
        DriveEventsV1.record(name, "DRIVE_" + label(channel) + "_FAILED", switches,
            "disconnectable=" + disconnectable + "; isolation not yet verified", false);
    }

    /** No motion is granted after lockout until a fresh hardware session. */
    public boolean permitMotion(boolean transferConditionsKnown) {
        return permitMotion(transferConditionsKnown,
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    /** Called by the Plant on every reaction, never blocks the SystemJ scheduler. */
    public boolean permitMotion(boolean transferConditionsKnown, long now) {
        boolean enabled = SystemWatchdogV1.isDriveFailoverEnabled();
        String command = !enabled && !"RUN".equals(phase) && !locked ? "STOPPING" : phase;
        mechanism.update(command, "CONNECTING_BACKUP".equals(phase) ? 1 - active : active, now);
        if (locked) return false;
        if ((!transferConditionsKnown && (failed[active] || !"RUN".equals(phase))) || mechanism.jammed())
            return lock("UNTRUSTED_POSITION_OR_LOAD");
        if (verificationPending && now - phaseAt > VERIFY_TIMEOUT_MS) return lock("ACTION_VERIFICATION_TIMEOUT");
        if (!failed[active] && "RUN".equals(phase)) return true;
        if (!enabled) return false;
        int standby = 1 - active;
        if ("RUN".equals(phase)) {
            DriveEventsV1.record(name, "FAULT_DETECTED", switches,
                "Active drive " + label(active) + " failed; stopping and holding load", false);
            if (failed[standby]) return lock("NO_SAFE_BACKUP");
            transition("STOPPING", now);
            return false;
        }
        if (failed[standby]) return lock("BACKUP_FAILED_DURING_TRANSFER");
        if (now - phaseAt > PHASE_TIMEOUT_MS) return lock(phase + "_TIMEOUT");
        if ("STOPPING".equals(phase) && mechanism.stoppedAndHeld()) {
            transition("ISOLATING_PRIMARY", now);
        } else if ("ISOLATING_PRIMARY".equals(phase) && mechanism.connectedChannel() == -1 && mechanism.stoppedAndHeld()) {
            isolated[active] = true;
            transition("CONNECTING_BACKUP", now);
        } else if ("CONNECTING_BACKUP".equals(phase) && mechanism.connectedChannel() == standby && mechanism.stoppedAndHeld()) {
            active = standby;
            switches++;
            verificationPending = true;
            phase = "RUN";
            phaseAt = now;
            result = "VERIFYING";
            DriveEventsV1.record(name, "SWITCH_TO_" + label(active), switches,
                "Isolation and coupling verified; awaiting original action completion", false);
        }
        // Never charge the transfer reaction itself as powered movement.
        return false;
    }

    private void transition(String next, long now) {
        phase = next;
        phaseAt = now;
        result = next;
        DriveEventsV1.record(name, "TRANSFER_PHASE", switches, next, false);
    }

    private boolean lock(String reason) {
        locked = true;
        phase = "SAFE_ERROR";
        result = reason;
        DriveEventsV1.record(name, "SAFE_ERROR", switches, reason, true);
        return false;
    }

    public void inhibit(String reason) { if (!locked) lock(reason); }

    public void completed() {
        if (!verificationPending || locked || failed[active]) return;
        verificationPending = false;
        result = "AUTO_FAILOVER_VERIFIED";
        DriveEventsV1.record(name, "FAILOVER_VERIFIED", switches,
            "Current physical action completed by " + label(active), false);
    }

    public void interruptAction() {
        if (verificationPending) {
            verificationPending = false;
            result = "INTERRUPTED_NOT_VERIFIED";
            DriveEventsV1.record(name, "ACTION_INTERRUPTED", switches,
                "Failover completion not verified", true);
        }
    }



    public int switchCount() { return switches; }
    public String visualTelemetry() {
        return label(active) + "," + failed[0] + "," + failed[1] + "," + monitoringState() + "," + switches;
    }
    public int activeChannel() { return active; }
    public boolean isLocked() { return locked; }
    public String monitoringState() {
        if (locked) return "SAFE_ERROR";
        if (!"RUN".equals(phase)) return phase;
        if (failed[active]) return "DRIVE_FAULT_PENDING";
        if (verificationPending) return "FAILOVER_VERIFYING";
        return failed[0] || failed[1] ? "DEGRADED" : "AVAILABLE";
    }
    public FaultMonitoringStateV2_1.ComponentSnapshot monitoringSnapshot() {
        return new FaultMonitoringStateV2_1.ComponentSnapshot(name, "M3",
            monitoringState(), "LOCAL DRIVE STATE", -1L, -1L,
            "active=" + label(active) + " | A=" + (failed[0] ? "FAILED" : "OK") +
            " B=" + (failed[1] ? "FAILED" : "OK") + " | switches=" + switches + " | " + result +
            " | " + mechanism.snapshot());
    }
    public String snapshot() {
        return name + " | active=" + label(active) + " | A=" + health(0) +
            " B=" + health(1) + " | switches=" + switches + " | " +
            (locked ? "SAFE / ERROR - NO SAFE BACKUP" : verificationPending ?
                "VERIFYING ACTION" : failed[0] || failed[1] ? result : "AVAILABLE") + " | " + mechanism.snapshot();
    }
    private String health(int channel) {
        return failed[channel] ? (isolated[channel] ? "FAILED/ISOLATED" : "FAILED/NOT ISOLATED") : "HEALTHY";
    }
    private static String label(int channel) { return channel == 0 ? "A" : "B"; }
    private static void check(int channel) {
        if (channel < 0 || channel > 1) throw new IllegalArgumentException("drive must be A or B");
    }
}
