import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/** Geometry-aware, bottle-correlated Capper controller state machine. */
public final class CapperControllerModelV1 {
    private enum Stage {
        WAITING,
        POSITIONING,
        CLAMPING,
        LOWERING,
        GRIPPING,
        TWISTING,
        RELEASING,
        RETURNING,
        RAISING,
        UNCLAMPING,
        DONE,
        FAULT
    }

    private final long timeoutMs;
    private final Queue<String> plantCommands = new ArrayDeque<String>();
    private final Set<String> completedBottleIds = new HashSet<String>();
    private Stage stage = Stage.WAITING;
    private int status = M4StatusV1.READY;
    private M4BottleContextV1 activeContext;
    private long stageStartMs;
    private String completion;
    private String faultReason = "-";
    private String lastPlantFeedback;

    public CapperControllerModelV1(long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeoutMs = timeoutMs;
    }

    public boolean acceptBottleAtCap(String payload, long nowMs) {
        M4BottleContextV1 context;
        try {
            context = M4BottleContextV1.parse(payload);
        }
        catch (IllegalArgumentException exception) {
            fail("INVALID_CONTEXT", nowMs);
            return false;
        }
        if (completedBottleIds.contains(context.getBottleId())) {
            return false;
        }
        if (stage == Stage.FAULT) {
            return false;
        }
        if (status == M4StatusV1.BUSY) {
            if (activeContext != null && activeContext.equals(context)) {
                return false;
            }
            fail("ACTIVE_BOTTLE_MISMATCH", nowMs);
            return false;
        }
        activeContext = context;
        stage = Stage.POSITIONING;
        status = M4StatusV1.BUSY;
        stageStartMs = nowMs;
        completion = null;
        faultReason = "-";
        lastPlantFeedback = null;
        queue("SET_GEOMETRY", context.getGeometryProfileId());
        return true;
    }

    public void acceptPlantFeedback(String payload, long nowMs) {
        if (payload != null && payload.equals(lastPlantFeedback)) {
            return;
        }
        String[] fields;
        try {
            fields = M4ProtocolV1.fields(payload, 3);
        }
        catch (IllegalArgumentException exception) {
            fail("MALFORMED_PLANT_FEEDBACK", nowMs);
            return;
        }
        if (activeContext == null ||
            !activeContext.getBottleId().equals(fields[0])) {
            fail("PLANT_IDENTITY_MISMATCH", nowMs);
            return;
        }
        String event = fields[1];
        String value = fields[2];
        lastPlantFeedback = payload;
        if ("FAULT".equals(event)) {
            fail(value, nowMs);
            return;
        }
        if (stage == Stage.POSITIONING &&
            "PROFILE_CONFIRMED".equals(event) &&
            activeContext.getGeometryProfileId().equals(value)) {
            transition(Stage.CLAMPING, "CLAMP", "-");
        }
        else if (stage == Stage.CLAMPING && "CLAMPED".equals(event)) {
            transition(Stage.LOWERING, "LOWER", "-");
        }
        else if (stage == Stage.LOWERING && "LOWERED".equals(event)) {
            transition(Stage.GRIPPING, "GRIP", "-");
        }
        else if (stage == Stage.GRIPPING && "GRIPPED".equals(event)) {
            transition(Stage.TWISTING, "TWIST", "-");
        }
        else if (stage == Stage.TWISTING && "TWISTED".equals(event)) {
            transition(Stage.RELEASING, "RELEASE", "-");
        }
        else if (stage == Stage.RELEASING && "RELEASED".equals(event)) {
            transition(Stage.RETURNING, "RETURN_HOME", "-");
        }
        else if (stage == Stage.RETURNING && "HOME".equals(event)) {
            transition(Stage.RAISING, "RAISE", "-");
        }
        else if (stage == Stage.RAISING && "RAISED".equals(event)) {
            transition(Stage.UNCLAMPING, "UNCLAMP", "-");
        }
        else if (stage == Stage.UNCLAMPING && "UNCLAMPED".equals(event)) {
            stage = Stage.DONE;
            status = M4StatusV1.DONE;
            completedBottleIds.add(activeContext.getBottleId());
            completion = activeContext.getBottleId();
        }
        else {
            fail("UNEXPECTED_PLANT_FEEDBACK", nowMs);
        }
        stageStartMs = nowMs;
    }

    public void tick(long nowMs) {
        if (status == M4StatusV1.BUSY &&
            nowMs - stageStartMs > timeoutMs) {
            fail("TIMEOUT", nowMs);
        }
    }

    public String takePlantCommand() {
        return plantCommands.poll();
    }

    public String takeCompletion() {
        String result = completion;
        completion = null;
        return result;
    }

    public int getStatus() {
        return status;
    }

    public String getStageName() {
        return stage.name();
    }

    public String getActiveBottleId() {
        return activeContext == null ? "-" : activeContext.getBottleId();
    }

    public String getGeometryProfile() {
        return activeContext == null ? "-" :
            activeContext.getGeometryProfileId();
    }

    public String getFaultReason() {
        return faultReason;
    }

    public String snapshot() {
        return "Capper[status=" + M4StatusV1.nameOf(status) +
            ",stage=" + stage + ",bottle=" + getActiveBottleId() +
            ",geometry=" + getGeometryProfile() +
            ",fault=" + faultReason + "]";
    }

    public boolean resetFault() {
        if (stage != Stage.FAULT) {
            return false;
        }
        activeContext = null;
        stage = Stage.WAITING;
        status = M4StatusV1.READY;
        completion = null;
        faultReason = "-";
        plantCommands.clear();
        lastPlantFeedback = null;
        return true;
    }

    private void transition(Stage next, String action, String value) {
        stage = next;
        queue(action, value);
    }

    private void queue(String action, String value) {
        plantCommands.add(
            activeContext.getBottleId() + "|" + action + "|" + value
        );
    }

    private void fail(String reason, long nowMs) {
        if (stage != Stage.FAULT && activeContext != null) {
            queue("SAFE_STOP", reason);
        }
        stage = Stage.FAULT;
        status = M4StatusV1.FAULT;
        stageStartMs = nowMs;
        completion = null;
        faultReason = reason == null || reason.isEmpty() ? "FAULT" : reason;
    }
}
