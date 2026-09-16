import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/** Simulated Capper mechanics with clamp and raised-position interlocks. */
public final class CapperPlantModelV1 {
    private enum Stage {
        IDLE,
        POSITIONING,
        POSITIONED,
        CLAMPING,
        CLAMPED,
        LOWERING,
        LOWERED,
        GRIPPING,
        GRIPPED,
        TWISTING,
        TWISTED,
        RELEASING,
        RELEASED,
        RETURNING,
        HOME,
        RAISING,
        RAISED,
        UNCLAMPING,
        COMPLETE,
        FAULT
    }

    private final long actionDelayMs;
    private final long resetActionDelayMs;
    private final Queue<String> feedback = new ArrayDeque<String>();
    private final Set<String> acceptedCommands = new HashSet<String>();
    private final Set<String> retiredBottleIds = new HashSet<String>();
    private Stage stage = Stage.IDLE;
    private String activeBottleId;
    private String geometryProfile = "-";
    private String gripZSetpoint = "-";
    private String clampSetpoint = "-";
    private boolean positionConfirmed;
    private long stageStartMs;
    private boolean clamped;
    private boolean lowered;
    private boolean gripping;
    private boolean twisted;
    private String pendingFeedback;
    private String forcedFaultAction;
    private String lastAcceptedCommand;
    private int resetStep;
    private String resetEvidence = "NONE";

    public CapperPlantModelV1(long actionDelayMs) {
        this(actionDelayMs, actionDelayMs);
    }

    /** Separate demonstration pacing from the safety-home sensor delays. */
    public CapperPlantModelV1(long actionDelayMs, long resetActionDelayMs) {
        if (actionDelayMs < 0 || resetActionDelayMs < 0) {
            throw new IllegalArgumentException("negative action delay");
        }
        this.actionDelayMs = actionDelayMs;
        this.resetActionDelayMs = resetActionDelayMs;
    }

    public boolean acceptCommand(String payload, long nowMs) {
        if (resetStep != 0) { return false; }
        String[] fields;
        try {
            fields = M4ProtocolV1.fields(payload, 3);
            M4ProtocolV1.validateBottleId(fields[0]);
        }
        catch (IllegalArgumentException exception) {
            fault("UNKNOWN", "MALFORMED_COMMAND");
            return false;
        }
        String bottleId = fields[0];
        String action = fields[1];
        String value = fields[2];
        if (retiredBottleIds.contains(bottleId) ||
            acceptedCommands.contains(payload)) {
            return true;
        }
        if ("SAFE_STOP".equals(action)) {
            activeBottleId = bottleId;
            positionConfirmed = false;
            gripping = false;
            twisted = false;
            // Keep the clamp if the gripper is not safely raised.
            if (!lowered) {
                clamped = false;
            }
            stage = Stage.FAULT;
            remember(payload);
            return true;
        }
        if (activeBottleId != null && !activeBottleId.equals(bottleId) &&
            stage != Stage.IDLE && stage != Stage.COMPLETE) {
            fault(bottleId, "PLANT_IDENTITY_MISMATCH");
            return false;
        }
        if (forcedFaultAction != null && forcedFaultAction.equals(action)) {
            fault(bottleId, action + "_FAULT");
            return false;
        }
        if ("SET_GEOMETRY".equals(action) &&
            (stage == Stage.IDLE || stage == Stage.COMPLETE)) {
            final M4GeometryProfileV1 profile;
            try {
                profile = M4GeometryProfileV1.forId(value);
            }
            catch (IllegalArgumentException exception) {
                fault(bottleId, "UNKNOWN_GEOMETRY");
                return false;
            }
            retirePreviousBottle(bottleId);
            activeBottleId = bottleId;
            geometryProfile = value;
            gripZSetpoint = profile.getCapperGripZ();
            clampSetpoint = profile.getClampSetpoint();
            positionConfirmed = false;
            clamped = false;
            lowered = false;
            gripping = false;
            twisted = false;
            begin(Stage.POSITIONING, "PROFILE_CONFIRMED|" + value, nowMs);
            remember(payload);
            return true;
        }
        if ("CLAMP".equals(action) && stage == Stage.POSITIONED &&
            positionConfirmed) {
            begin(Stage.CLAMPING, "CLAMPED|-", nowMs);
            remember(payload);
            return true;
        }
        if ("LOWER".equals(action) && stage == Stage.CLAMPED && clamped) {
            begin(Stage.LOWERING, "LOWERED|-", nowMs);
            remember(payload);
            return true;
        }
        if ("GRIP".equals(action) && stage == Stage.LOWERED && clamped) {
            begin(Stage.GRIPPING, "GRIPPED|-", nowMs);
            remember(payload);
            return true;
        }
        if ("TWIST".equals(action) && stage == Stage.GRIPPED && clamped &&
            lowered && gripping) {
            begin(Stage.TWISTING, "TWISTED|-", nowMs);
            remember(payload);
            return true;
        }
        if ("RELEASE".equals(action) && stage == Stage.TWISTED && clamped) {
            begin(Stage.RELEASING, "RELEASED|-", nowMs);
            remember(payload);
            return true;
        }
        if ("RETURN_HOME".equals(action) && stage == Stage.RELEASED &&
            clamped) {
            begin(Stage.RETURNING, "HOME|-", nowMs);
            remember(payload);
            return true;
        }
        if ("RAISE".equals(action) && stage == Stage.HOME && clamped) {
            begin(Stage.RAISING, "RAISED|-", nowMs);
            remember(payload);
            return true;
        }
        if ("UNCLAMP".equals(action) && stage == Stage.RAISED &&
            clamped && !lowered) {
            begin(Stage.UNCLAMPING, "UNCLAMPED|-", nowMs);
            remember(payload);
            return true;
        }
        fault(bottleId, "INTERLOCK_OR_SEQUENCE_ERROR");
        return false;
    }

    public void tick(long nowMs) {
        if (resetStep != 0) {
            tickSystemReset(nowMs);
            return;
        }
        if (pendingFeedback == null || nowMs - stageStartMs < actionDelayMs) {
            return;
        }
        switch (stage) {
            case POSITIONING:
                stage = Stage.POSITIONED;
                positionConfirmed = true;
                break;
            case CLAMPING:
                clamped = true;
                stage = Stage.CLAMPED;
                break;
            case LOWERING:
                lowered = true;
                stage = Stage.LOWERED;
                break;
            case GRIPPING:
                gripping = true;
                stage = Stage.GRIPPED;
                break;
            case TWISTING:
                twisted = true;
                stage = Stage.TWISTED;
                break;
            case RELEASING:
                gripping = false;
                stage = Stage.RELEASED;
                break;
            case RETURNING:
                twisted = false;
                stage = Stage.HOME;
                break;
            case RAISING:
                lowered = false;
                stage = Stage.RAISED;
                break;
            case UNCLAMPING:
                clamped = false;
                stage = Stage.COMPLETE;
                break;
            default:
                fault(activeBottleId, "INVALID_TRANSITION");
                return;
        }
        feedback.add(activeBottleId + "|" + pendingFeedback);
        pendingFeedback = null;
    }

    public String takeFeedback() {
        return feedback.poll();
    }

    public boolean isClamped() {
        return clamped;
    }

    public boolean isLowered() {
        return lowered;
    }

    public boolean isGripping() {
        return gripping;
    }

    public String getGeometryProfile() {
        return geometryProfile;
    }

    public String getGripZSetpoint() {
        return gripZSetpoint;
    }

    public String getClampSetpoint() {
        return clampSetpoint;
    }

    public boolean isPositionConfirmed() {
        return positionConfirmed;
    }

    public String getStageName() {
        return stage.name();
    }

    public String snapshot() {
        return "CapperPlant[stage=" + stage + ",bottle=" + activeBottleId +
            ",geometry=" + geometryProfile + ",gripZ=" + gripZSetpoint +
            ",clampSetpoint=" + clampSetpoint +
            ",positionConfirmed=" + positionConfirmed +
            ",clamped=" + clamped +
            ",lowered=" + lowered + ",gripping=" + gripping +
            ",twisted=" + twisted + "]";
    }

    public void setForcedFaultAction(String action) {
        forcedFaultAction = action;
    }

    public void clearFaults() {
        forcedFaultAction = null;
        pendingFeedback = null;
        feedback.clear();
        activeBottleId = null;
        geometryProfile = "-";
        gripZSetpoint = "-";
        clampSetpoint = "-";
        positionConfirmed = false;
        clamped = false;
        lowered = false;
        gripping = false;
        twisted = false;
        stage = Stage.IDLE;
        lastAcceptedCommand = null;
        acceptedCommands.clear();
        retiredBottleIds.clear();
    }

    public void beginSystemReset(long nowMs) {
        if (resetStep != 0) { return; }
        // A lowering/clamping stroke may be partly complete even if its
        // completion sensor has not fired. Conservatively retain the clamp.
        if (stage == Stage.LOWERING || lowered) {
            lowered = true;
            clamped = true;
        }
        if (stage == Stage.CLAMPING) { clamped = true; }
        gripping = false;
        pendingFeedback = null;
        feedback.clear();
        resetEvidence = "GRIP_TWIST_STOPPED";
        stage = Stage.RETURNING;
        resetStep = 1;
        stageStartMs = nowMs;
    }

    public void tickSystemReset(long nowMs) {
        if (resetStep == 0 || nowMs - stageStartMs < resetActionDelayMs) {
            return;
        }
        if (resetStep == 1) {
            twisted = false;
            resetEvidence += ",HOME_CONFIRMED";
            stage = Stage.RAISING;
            resetStep = 2;
        }
        else if (resetStep == 2) {
            lowered = false;
            resetEvidence += ",RAISED_CONFIRMED";
            stage = Stage.UNCLAMPING;
            resetStep = 3;
        }
        else {
            // Release the bottle only after the simulated raised sensor.
            if (lowered) { return; }
            clamped = false;
            resetEvidence += ",UNCLAMPED_CONFIRMED";
            resetStep = 0;
            clearFaults();
        }
        stageStartMs = nowMs;
    }

    public boolean isSystemResetSafe() {
        return resetStep == 0 && stage == Stage.IDLE && !clamped &&
            !lowered && !gripping && !twisted && pendingFeedback == null &&
            feedback.isEmpty();
    }

    public String systemResetEvidence() { return resetEvidence; }

    private void begin(Stage next, String eventAndValue, long nowMs) {
        stage = next;
        pendingFeedback = eventAndValue;
        stageStartMs = nowMs;
    }

    private void retirePreviousBottle(String nextBottleId) {
        if (activeBottleId != null && !activeBottleId.equals(nextBottleId)) {
            retiredBottleIds.add(activeBottleId);
            acceptedCommands.clear();
        }
    }

    private void remember(String payload) {
        acceptedCommands.add(payload);
        lastAcceptedCommand = payload;
    }

    private void fault(String bottleId, String reason) {
        gripping = false;
        twisted = false;
        positionConfirmed = false;
        stage = Stage.FAULT;
        pendingFeedback = null;
        feedback.add(bottleId + "|FAULT|" + reason);
    }
}
