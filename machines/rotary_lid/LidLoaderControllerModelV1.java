/** Deterministic pick-and-place controller model for the lid loader. */
public final class LidLoaderControllerModelV1 {
    public static final long PICK_TIMEOUT_MS = 1000;
    public static final long PLACE_TIMEOUT_MS = 1000;

    public enum State {
        READY,
        PICKING,
        PLACING,
        DONE,
        FAULT
    }

    private State state = State.READY;
    private long stateElapsedMs = 0;
    private boolean pickActuatorEnabled = false;
    private boolean placeActuatorEnabled = false;
    private String faultReason = "";

    /** Starts only when a bottle and a lid are both available. */
    public boolean requestLoad(
        boolean bottleAtLidPosition,
        boolean lidAvailable
    ) {
        if (state != State.READY || !bottleAtLidPosition) {
            return false;
        }
        if (!lidAvailable) {
            fail("lid magazine empty");
            return false;
        }

        state = State.PICKING;
        stateElapsedMs = 0;
        pickActuatorEnabled = true;
        return true;
    }

    /** Advances the pick/place sequence using plant confirmation sensors. */
    public void tick(long elapsedMs, boolean lidPicked, boolean lidPlaced) {
        if (elapsedMs < 0) {
            throw new IllegalArgumentException("elapsedMs must be non-negative");
        }

        if (state == State.PICKING) {
            if (lidPicked) {
                state = State.PLACING;
                stateElapsedMs = 0;
                pickActuatorEnabled = false;
                placeActuatorEnabled = true;
            }
            else {
                stateElapsedMs += elapsedMs;
                if (stateElapsedMs >= PICK_TIMEOUT_MS) {
                    fail("lid pick timeout");
                }
            }
        }
        else if (state == State.PLACING) {
            if (lidPlaced) {
                state = State.DONE;
                stateElapsedMs = 0;
                placeActuatorEnabled = false;
            }
            else {
                stateElapsedMs += elapsedMs;
                if (stateElapsedMs >= PLACE_TIMEOUT_MS) {
                    fail("lid placement timeout");
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

    public boolean resetFault(boolean lidAvailable) {
        if (state != State.FAULT || !lidAvailable) {
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
            case PICKING:
            case PLACING:
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

    public boolean isPickActuatorEnabled() {
        return pickActuatorEnabled;
    }

    public boolean isPlaceActuatorEnabled() {
        return placeActuatorEnabled;
    }

    public String getFaultReason() {
        return faultReason;
    }

    private void fail(String reason) {
        state = State.FAULT;
        pickActuatorEnabled = false;
        placeActuatorEnabled = false;
        faultReason = reason;
    }
}
