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

    /** Starts a step unless a completed capped bottle blocks position 1. */
    public boolean requestRotation(boolean capOnBottleAtPosition1) {
        if (state != State.READY || capOnBottleAtPosition1) {
            return false;
        }

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

    public boolean resetFault(boolean tableAlignedWithSensor) {
        if (state != State.FAULT || !tableAlignedWithSensor) {
            return false;
        }
        state = State.READY;
        stateElapsedMs = 0;
        faultReason = "";
        return true;
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

    private void fail(String reason) {
        state = State.FAULT;
        motorEnabled = false;
        faultReason = reason;
    }
}
