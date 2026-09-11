/** Deterministic controller model for one 60-degree rotary-table step. */
public final class RotaryControllerModelV1 {
    public static final long ROTATION_TIME_MS = 500;
    public static final long ALIGNMENT_TIMEOUT_MS = 250;

    public enum State {
        READY,
        ROTATING,
        VERIFYING_ALIGNMENT,
        DONE,
        FAULT
    }

    private State state = State.READY;
    private long stateElapsedMs = 0;
    private int tablePosition = 0;
    private boolean motorEnabled = false;
    private String faultReason = "";
    private long activeCycleId;
    private long lastCompletedCycleId;
    private long faultSequence;
    private String faultEventId;

    /** Starts one M3-sequenced step after the Plant station barrier passes. */
    public boolean requestRotation(long cycleId, boolean barrierSatisfied) {
        if (state != State.READY || !barrierSatisfied || cycleId <= 0 ||
            cycleId <= lastCompletedCycleId) {
            return false;
        }

        activeCycleId = cycleId;
        state = State.ROTATING;
        stateElapsedMs = 0;
        motorEnabled = true;
        return true;
    }

    /** Advances simulated time and applies the current alignment sensor. */
    public void tick(long elapsedMs, boolean tableAlignedWithSensor) {
        if (elapsedMs < 0) {
            throw new IllegalArgumentException("elapsedMs must be non-negative");
        }

        if (state == State.ROTATING) {
            stateElapsedMs += elapsedMs;
            if (stateElapsedMs >= ROTATION_TIME_MS) {
                state = State.VERIFYING_ALIGNMENT;
                stateElapsedMs = 0;
                motorEnabled = false;
            }
        }
        else if (state == State.VERIFYING_ALIGNMENT) {
            if (tableAlignedWithSensor) {
                tablePosition = (tablePosition + 1) % 6;
                lastCompletedCycleId = activeCycleId;
                state = State.DONE;
                stateElapsedMs = 0;
            }
            else {
                stateElapsedMs += elapsedMs;
                if (stateElapsedMs >= ALIGNMENT_TIMEOUT_MS) {
                    fail("table alignment timeout");
                }
            }
        }
    }

    public boolean acknowledgeDone() {
        if (state != State.DONE) {
            return false;
        }
        state = State.READY;
        return true;
    }

    public boolean resetFault(RotaryRecoveryEvidenceV1 evidence) {
        if (state != State.FAULT || evidence == null ||
            !evidence.permitsReset()) {
            return false;
        }
        state = State.READY;
        stateElapsedMs = 0;
        faultReason = "";
        faultEventId = null;
        return true;
    }

    public long getActiveCycleId() {
        return activeCycleId;
    }

    public long getLastCompletedCycleId() {
        return lastCompletedCycleId;
    }

    public int getStatus() {
        switch (state) {
            case READY:
                return Member3MachineStateV1.READY;
            case ROTATING:
            case VERIFYING_ALIGNMENT:
                return Member3MachineStateV1.BUSY;
            case DONE:
                return Member3MachineStateV1.DONE;
            default:
                return Member3MachineStateV1.FAULT;
        }
    }

    public State getState() {
        return state;
    }

    public int getTablePosition() {
        return tablePosition;
    }

    public boolean isMotorEnabled() {
        return motorEnabled;
    }

    public String getFaultReason() {
        return faultReason;
    }

    public String getFaultEventId() {
        return faultEventId;
    }

    private void fail(String reason) {
        state = State.FAULT;
        motorEnabled = false;
        faultReason = reason;
        faultSequence++;
        faultEventId = "ROTARY-" + faultSequence;
    }
}
