/** Deterministic pick-and-place controller model for the lid loader. */
public final class LidLoaderControllerModelV1 {
    public static final long PICK_TIMEOUT_MS = SimulationTiming.scaleMillis(1000L);
    public static final long PLACE_TIMEOUT_MS = SimulationTiming.scaleMillis(1000L);

    public enum State {
        READY,
        PICKING,
        PLACING,
        DONE,
        FAULT
    }

    public enum Fault {
        NONE,
        MAGAZINE_EMPTY,
        PICK_TIMEOUT,
        PLACEMENT_TIMEOUT,
        LID_SENSOR_FAULT
    }

    private volatile State state = State.READY;
    private long stateElapsedMs = 0;
    private boolean pickActuatorEnabled = false;
    private boolean placeActuatorEnabled = false;
    private String faultReason = "";
    private Fault fault = Fault.NONE;
    private String activeBottleId;
    private String completedBottleId;
    private long faultSequence;
    private String faultEventId;
    private boolean retryingPick;

    public LidLoaderControllerModelV1() {
        this(0L);
    }

    /** Independent standby checkpoint; immutable strings and scalar state only. */
    public LidLoaderControllerModelV1(LidLoaderControllerModelV1 source) {
        state = source.state;
        stateElapsedMs = source.stateElapsedMs;
        pickActuatorEnabled = source.pickActuatorEnabled;
        placeActuatorEnabled = source.placeActuatorEnabled;
        faultReason = source.faultReason;
        fault = source.fault;
        activeBottleId = source.activeBottleId;
        completedBottleId = source.completedBottleId;
        faultSequence = source.faultSequence;
        faultEventId = source.faultEventId;
        retryingPick = source.retryingPick;
    }

    public LidLoaderControllerModelV1(long initialFaultSequence) {
        faultSequence = Math.max(0L, initialFaultSequence);
    }

    public boolean requestLoad(
        String bottleId,
        boolean lidAvailable
    ) {
        if (state != State.READY || bottleId == null) {
            return false;
        }
        BottleContextV1.validateBottleId(bottleId);
        activeBottleId = bottleId;
        if (!lidAvailable) {
            fail(Fault.MAGAZINE_EMPTY, "lid magazine empty");
            return false;
        }

        state = State.PICKING;
        stateElapsedMs = 0;
        pickActuatorEnabled = true;
        return true;
    }

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
                    fail(Fault.PICK_TIMEOUT, "lid pick timeout");
                }
            }
        }
        else if (state == State.PLACING) {
            if (lidPlaced) {
                state = State.DONE;
                stateElapsedMs = 0;
                placeActuatorEnabled = false;
                completedBottleId = activeBottleId;
            }
            else {
                stateElapsedMs += elapsedMs;
                if (stateElapsedMs >= PLACE_TIMEOUT_MS) {
                    fail(Fault.PLACEMENT_TIMEOUT, "lid placement timeout");
                }
            }
        }
    }

    public boolean acknowledgeDone() {
        if (state != State.DONE) {
            return false;
        }
        activeBottleId = null;
        completedBottleId = null;
        retryingPick = false;
        state = State.READY;
        return true;
    }

    public boolean resetFault(LidRecoveryEvidenceV1 evidence) {
        if (state != State.FAULT || evidence == null ||
            !evidence.permitsReset(fault)) {
            return false;
        }
        state = State.READY;
        stateElapsedMs = 0;
        faultReason = "";
        fault = Fault.NONE;
        activeBottleId = null;
        faultEventId = null;
        return true;
    }

    /** Continue the same bottle; a second failure retains the original event budget. */
    public boolean retryPick(String eventId) {
        if (retryingPick || state != State.FAULT || fault != Fault.PICK_TIMEOUT ||
            eventId == null || !eventId.equals(faultEventId)) return false;
        retryingPick = true;
        state = State.PICKING;
        stateElapsedMs = 0;
        fault = Fault.NONE;
        faultReason = "";
        pickActuatorEnabled = true;
        placeActuatorEnabled = false;
        return true;
    }

    public boolean isRetryingPick() { return retryingPick; }

    public void reportLidSensorFault() {
        if (state != State.FAULT) {
            fail(Fault.LID_SENSOR_FAULT, "lid placement sensor fault");
        }
    }

    public boolean injectFault(Fault injectedFault) {
        if (injectedFault == null || injectedFault == Fault.NONE ||
            (state != State.PICKING && state != State.PLACING)) {
            return false;
        }
        fail(injectedFault,
            "injected " + injectedFault.name().toLowerCase().replace('_', ' '));
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

    public Fault getFault() {
        return fault;
    }

    public String getActiveBottleId() {
        return activeBottleId;
    }

    public String getFaultEventId() {
        return faultEventId;
    }

    public long getFaultSequence() {
        return faultSequence;
    }

    public String takeCompletedBottleId() {
        if (state != State.DONE || completedBottleId == null) {
            return null;
        }
        String result = completedBottleId;
        completedBottleId = null;
        return result;
    }

    private void fail(Fault faultValue, String reason) {
        state = State.FAULT;
        pickActuatorEnabled = false;
        placeActuatorEnabled = false;
        fault = faultValue;
        faultReason = reason;
        if (!retryingPick) {
            faultSequence++;
            faultEventId = "LID-" + faultSequence;
        }
    }
}
