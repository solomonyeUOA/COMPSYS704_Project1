import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/** Downstream S/L sorting and package counting; never owns BOTTLE_DONE. */
public final class SortPackControllerModelV1 {
    private enum Stage {
        WAITING,
        ROUTING,
        PLACING,
        DONE,
        FAULT
    }

    private final int smallPackageCapacity;
    private final int largePackageCapacity;
    private final long timeoutMs;
    private final Queue<String> plantCommands = new ArrayDeque<String>();
    private final Set<String> completedBottleIds = new HashSet<String>();
    private Stage stage = Stage.WAITING;
    private M4BottleContextV1 activeContext;
    private int status = M4StatusV1.READY;
    private int smallBottleCount;
    private int largeBottleCount;
    private int smallPackageCount;
    private int largePackageCount;
    private long stageStartMs;
    private String completion;
    private String faultReason = "-";
    private String lastPlantFeedback;

    public SortPackControllerModelV1(
        int smallPackageCapacity,
        int largePackageCapacity,
        long timeoutMs
    ) {
        if (smallPackageCapacity <= 0 || largePackageCapacity <= 0 ||
            timeoutMs <= 0) {
            throw new IllegalArgumentException("invalid SortPack configuration");
        }
        this.smallPackageCapacity = smallPackageCapacity;
        this.largePackageCapacity = largePackageCapacity;
        this.timeoutMs = timeoutMs;
    }

    public boolean acceptBottleReady(String payload, long nowMs) {
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
        stage = Stage.ROUTING;
        status = M4StatusV1.BUSY;
        completion = null;
        faultReason = "-";
        lastPlantFeedback = null;
        stageStartMs = nowMs;
        queue("SET_LANE", expectedLane());
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
        lastPlantFeedback = payload;
        if ("FAULT".equals(fields[1])) {
            fail(fields[2], nowMs);
            return;
        }
        if (stage == Stage.ROUTING &&
            "LANE_CONFIRMED".equals(fields[1])) {
            if (!expectedLane().equals(fields[2])) {
                fail("WRONG_LANE", nowMs);
                return;
            }
            stage = Stage.PLACING;
            stageStartMs = nowMs;
            queue("PLACE", activeContext.getPackagingProfileId());
            return;
        }
        if (stage == Stage.PLACING && "PLACED".equals(fields[1]) &&
            activeContext.getPackagingProfileId().equals(fields[2])) {
            recordPlacement();
            return;
        }
        fail("UNEXPECTED_PLANT_FEEDBACK", nowMs);
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

    public int getSmallBottleCount() {
        return smallBottleCount;
    }

    public int getLargeBottleCount() {
        return largeBottleCount;
    }

    public int getSmallPackageCount() {
        return smallPackageCount;
    }

    public int getLargePackageCount() {
        return largePackageCount;
    }

    public String getStageName() {
        return stage.name();
    }

    public String getActiveBottleId() {
        return activeContext == null ? "-" : activeContext.getBottleId();
    }

    public String getFaultReason() {
        return faultReason;
    }

    public String snapshot() {
        return "SortPack[status=" + M4StatusV1.nameOf(status) +
            ",stage=" + stage + ",bottle=" + getActiveBottleId() +
            ",S=" + smallBottleCount + "/packages=" + smallPackageCount +
            ",L=" + largeBottleCount + "/packages=" + largePackageCount +
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

    private String expectedLane() {
        return M4BottleContextV1.SMALL.equals(activeContext.getSizeCode()) ?
            "LANE_S" : "LANE_L";
    }

    private void recordPlacement() {
        completedBottleIds.add(activeContext.getBottleId());
        if (M4BottleContextV1.SMALL.equals(activeContext.getSizeCode())) {
            smallBottleCount++;
            if (smallBottleCount % smallPackageCapacity == 0) {
                smallPackageCount++;
            }
        }
        else {
            largeBottleCount++;
            if (largeBottleCount % largePackageCapacity == 0) {
                largePackageCount++;
            }
        }
        stage = Stage.DONE;
        status = M4StatusV1.DONE;
        completion = activeContext.getBottleId() + "|SORT_PACK_COMPLETE|" +
            activeContext.getPackagingProfileId();
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
