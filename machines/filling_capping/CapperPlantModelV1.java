import java.util.ArrayDeque;
import java.util.Queue;

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
    private final Queue<String> feedback = new ArrayDeque<String>();
    private Stage stage = Stage.IDLE;
    private String activeBottleId;
    private String geometryProfile = "-";
    private long stageStartMs;
    private boolean clamped;
    private boolean lowered;
    private boolean gripping;
    private boolean twisted;
    private String pendingFeedback;
    private String forcedFaultAction;
    private String lastAcceptedCommand;

    public CapperPlantModelV1(long actionDelayMs) {
        if (actionDelayMs < 0) {
            throw new IllegalArgumentException("negative action delay");
        }
        this.actionDelayMs = actionDelayMs;
    }

    public boolean acceptCommand(String payload, long nowMs) {
        if (payload != null && payload.equals(lastAcceptedCommand)) {
            return true;
        }
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
        if ("SAFE_STOP".equals(action)) {
            activeBottleId = bottleId;
            gripping = false;
            twisted = false;
            // Keep the clamp if the gripper is not safely raised.
            if (!lowered) {
                clamped = false;
            }
            stage = Stage.FAULT;
            lastAcceptedCommand = payload;
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
            if (!M4BottleContextV1.GEOMETRY_SMALL.equals(value) &&
                !M4BottleContextV1.GEOMETRY_LARGE.equals(value)) {
                fault(bottleId, "UNKNOWN_GEOMETRY");
                return false;
            }
            activeBottleId = bottleId;
            geometryProfile = value;
            clamped = false;
            lowered = false;
            gripping = false;
            twisted = false;
            begin(Stage.POSITIONING, "PROFILE_CONFIRMED|" + value, nowMs);
            lastAcceptedCommand = payload;
            return true;
        }
        if ("CLAMP".equals(action) && stage == Stage.POSITIONED) {
            begin(Stage.CLAMPING, "CLAMPED|-", nowMs);
            lastAcceptedCommand = payload;
            return true;
        }
        if ("LOWER".equals(action) && stage == Stage.CLAMPED && clamped) {
            begin(Stage.LOWERING, "LOWERED|-", nowMs);
            lastAcceptedCommand = payload;
            return true;
        }
        if ("GRIP".equals(action) && stage == Stage.LOWERED && clamped) {
            begin(Stage.GRIPPING, "GRIPPED|-", nowMs);
            lastAcceptedCommand = payload;
            return true;
        }
        if ("TWIST".equals(action) && stage == Stage.GRIPPED && clamped &&
            lowered && gripping) {
            begin(Stage.TWISTING, "TWISTED|-", nowMs);
            lastAcceptedCommand = payload;
            return true;
        }
        if ("RELEASE".equals(action) && stage == Stage.TWISTED && clamped) {
            begin(Stage.RELEASING, "RELEASED|-", nowMs);
            lastAcceptedCommand = payload;
            return true;
        }
        if ("RETURN_HOME".equals(action) && stage == Stage.RELEASED &&
            clamped) {
            begin(Stage.RETURNING, "HOME|-", nowMs);
            lastAcceptedCommand = payload;
            return true;
        }
        if ("RAISE".equals(action) && stage == Stage.HOME && clamped) {
            begin(Stage.RAISING, "RAISED|-", nowMs);
            lastAcceptedCommand = payload;
            return true;
        }
        if ("UNCLAMP".equals(action) && stage == Stage.RAISED &&
            clamped && !lowered) {
            begin(Stage.UNCLAMPING, "UNCLAMPED|-", nowMs);
            lastAcceptedCommand = payload;
            return true;
        }
        fault(bottleId, "INTERLOCK_OR_SEQUENCE_ERROR");
        return false;
    }

    public void tick(long nowMs) {
        if (pendingFeedback == null || nowMs - stageStartMs < actionDelayMs) {
            return;
        }
        switch (stage) {
            case POSITIONING:
                stage = Stage.POSITIONED;
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

    public String getStageName() {
        return stage.name();
    }

    public String snapshot() {
        return "CapperPlant[stage=" + stage + ",bottle=" + activeBottleId +
            ",geometry=" + geometryProfile + ",clamped=" + clamped +
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
        clamped = false;
        lowered = false;
        gripping = false;
        twisted = false;
        stage = Stage.IDLE;
        lastAcceptedCommand = null;
    }

    private void begin(Stage next, String eventAndValue, long nowMs) {
        stage = next;
        pendingFeedback = eventAndValue;
        stageStartMs = nowMs;
    }

    private void fault(String bottleId, String reason) {
        gripping = false;
        twisted = false;
        stage = Stage.FAULT;
        pendingFeedback = null;
        feedback.add(bottleId + "|FAULT|" + reason);
    }
}
