/** Simulation only: independently disconnectable drives and a load-holding stop.
 * Commands and feedback are separate; elapsed time never overrides failed feedback.
 */
public final class DriveMechanismV1 {
    public static final long RESPONSE_MS = Math.max(1L, SimulationTiming.scaleMillis(20L));
    private String command = "RUN";
    private long commandedAt;
    private int connected;
    private boolean stopped;
    private boolean held;
    private String fault = "NONE";

    public void inject(String value) { fault = value; }

    public void update(String requested, int selected, long now) {
        if (!command.equals(requested)) {
            command = requested;
            commandedAt = now;
        }
        if ("RUN".equals(command)) {
            stopped = false;
            held = false;
            return;
        }
        if (now - commandedAt < RESPONSE_MS) return;
        if ("STOPPING".equals(command) || "SAFE_ERROR".equals(command)) {
            stopped = !"STOP_FAILURE".equals(fault);
            held = stopped && !"HOLD_FAILURE".equals(fault);
        } else if ("ISOLATING_PRIMARY".equals(command)) {
            if (stopped && held && !"ISOLATION_FAILURE".equals(fault)) connected = -1;
        } else if ("CONNECTING_BACKUP".equals(command)) {
            if (stopped && held && connected == -1 && !"ENGAGEMENT_FAILURE".equals(fault))
                connected = selected;
        }
    }

    public boolean stoppedAndHeld() { return stopped && held; }
    public int connectedChannel() { return connected; }
    public boolean jammed() { return "MECHANICAL_JAM".equals(fault); }
    public String snapshot() {
        return "coupling=" + (connected < 0 ? "NONE" : connected == 0 ? "A" : "B") +
            " stopped=" + stopped + " held=" + held + " mechanismFault=" + fault;
    }
}
