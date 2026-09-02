import java.util.ArrayDeque;
import java.util.Queue;

/** Simulated diverter and package placement Plant. */
public final class SortPackPlantModelV1 {
    private enum Stage {
        IDLE,
        ROUTING,
        ROUTED,
        PLACING,
        COMPLETE,
        FAULT
    }

    private final long routeDelayMs;
    private final long placeDelayMs;
    private final Queue<String> feedback = new ArrayDeque<String>();
    private Stage stage = Stage.IDLE;
    private String activeBottleId;
    private String lane = "-";
    private String packagingProfile = "-";
    private long stageStartMs;
    private boolean packagePresent = true;
    private boolean forceWrongLane;
    private boolean forcePlacementTimeout;
    private String lastAcceptedCommand;

    public SortPackPlantModelV1(long routeDelayMs, long placeDelayMs) {
        if (routeDelayMs < 0 || placeDelayMs < 0) {
            throw new IllegalArgumentException("negative Plant delay");
        }
        this.routeDelayMs = routeDelayMs;
        this.placeDelayMs = placeDelayMs;
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
            stage = Stage.FAULT;
            lastAcceptedCommand = payload;
            return true;
        }
        if ("SET_LANE".equals(action) &&
            (stage == Stage.IDLE || stage == Stage.COMPLETE)) {
            if (!"LANE_S".equals(value) && !"LANE_L".equals(value)) {
                fault(bottleId, "UNKNOWN_LANE");
                return false;
            }
            activeBottleId = bottleId;
            lane = value;
            stage = Stage.ROUTING;
            stageStartMs = nowMs;
            lastAcceptedCommand = payload;
            return true;
        }
        if ("PLACE".equals(action) && stage == Stage.ROUTED) {
            if (!packagePresent) {
                fault(bottleId, "PACKAGE_UNAVAILABLE");
                return false;
            }
            if (!M4BottleContextV1.PACKAGING_SMALL.equals(value) &&
                !M4BottleContextV1.PACKAGING_LARGE.equals(value)) {
                fault(bottleId, "UNKNOWN_PACKAGE_PROFILE");
                return false;
            }
            packagingProfile = value;
            stage = Stage.PLACING;
            stageStartMs = nowMs;
            lastAcceptedCommand = payload;
            return true;
        }
        fault(bottleId, "UNEXPECTED_COMMAND");
        return false;
    }

    public void tick(long nowMs) {
        if (stage == Stage.ROUTING &&
            nowMs - stageStartMs >= routeDelayMs) {
            stage = Stage.ROUTED;
            String confirmed = forceWrongLane ?
                ("LANE_S".equals(lane) ? "LANE_L" : "LANE_S") : lane;
            feedback.add(activeBottleId + "|LANE_CONFIRMED|" + confirmed);
        }
        else if (stage == Stage.PLACING && !forcePlacementTimeout &&
            nowMs - stageStartMs >= placeDelayMs) {
            stage = Stage.COMPLETE;
            feedback.add(
                activeBottleId + "|PLACED|" + packagingProfile
            );
        }
    }

    public String takeFeedback() {
        return feedback.poll();
    }

    public String getLane() {
        return lane;
    }

    public String getStageName() {
        return stage.name();
    }

    public String snapshot() {
        return "SortPackPlant[stage=" + stage + ",bottle=" +
            activeBottleId + ",lane=" + lane + ",package=" +
            packagingProfile + ",present=" + packagePresent + "]";
    }

    public void setPackagePresent(boolean present) {
        packagePresent = present;
    }

    public void setForceWrongLane(boolean active) {
        forceWrongLane = active;
    }

    public void setForcePlacementTimeout(boolean active) {
        forcePlacementTimeout = active;
    }

    public void clearFaults() {
        feedback.clear();
        activeBottleId = null;
        lane = "-";
        packagingProfile = "-";
        packagePresent = true;
        forceWrongLane = false;
        forcePlacementTimeout = false;
        stage = Stage.IDLE;
        lastAcceptedCommand = null;
    }

    private void fault(String bottleId, String reason) {
        stage = Stage.FAULT;
        feedback.add(bottleId + "|FAULT|" + reason);
    }
}
