import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/** Batch-isolated S/L sorting and package counting; never owns BOTTLE_DONE. */
public final class SortPackControllerModelV1 {
    private enum Stage {
        WAITING,
        ROUTING,
        PLACING,
        DONE,
        FAULT
    }

    private static final class BatchState {
        private final String batchId;
        private String sizeCode;
        private int expectedQuantity;
        private int placed;
        private int packages;
        private boolean endReceived;
        private boolean finalised;

        private BatchState(String id, String size) {
            batchId = id;
            sizeCode = size;
        }
    }

    private final int smallPackageCapacity;
    private final int largePackageCapacity;
    private final long timeoutMs;
    private final Queue<String> plantCommands = new ArrayDeque<String>();
    private final Queue<String> batchCompletions =
        new ArrayDeque<String>();
    private final Set<String> completedBottleIds = new HashSet<String>();
    private final Map<String, BatchState> batches =
        new LinkedHashMap<String, BatchState>();
    private Stage stage = Stage.WAITING;
    private M4BottleContextV1 activeContext;
    private String activeBatchId;
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

        String batchId = batchIdForBottle(context.getBottleId());
        BatchState batch = batch(batchId, context.getSizeCode());
        if (batch.finalised) {
            fail("BATCH_ALREADY_FINALISED", nowMs);
            return false;
        }
        if (!context.getSizeCode().equals(batch.sizeCode)) {
            fail("BATCH_SIZE_MISMATCH", nowMs);
            return false;
        }
        if (batch.endReceived && batch.placed >= batch.expectedQuantity) {
            fail("BATCH_QUANTITY_EXCEEDED", nowMs);
            return false;
        }

        activeContext = context;
        activeBatchId = batchId;
        stage = Stage.ROUTING;
        status = M4StatusV1.BUSY;
        completion = null;
        faultReason = "-";
        lastPlantFeedback = null;
        stageStartMs = nowMs;
        queue("SET_LANE", expectedLane());
        return true;
    }

    /**
     * Accepts M1's batchId|quantity|size boundary. The boundary may arrive
     * before the last bottle; partial packaging waits for all expected
     * physical placements and duplicate boundaries are idempotent.
     */
    public boolean acceptBatchEnd(String payload, long nowMs) {
        final String[] fields;
        final int quantity;
        try {
            fields = M4ProtocolV1.fields(payload, 3);
            M4ProtocolV1.validateBottleId(fields[0]);
            quantity = M4ProtocolV1.unsignedInteger(fields[1], "quantity");
            if (quantity < 1 ||
                (!M4BottleContextV1.SMALL.equals(fields[2]) &&
                 !M4BottleContextV1.LARGE.equals(fields[2]))) {
                throw new IllegalArgumentException("invalid batch end");
            }
        }
        catch (IllegalArgumentException invalid) {
            return false;
        }

        BatchState batch = batch(fields[0], fields[2]);
        if (!fields[2].equals(batch.sizeCode)) {
            fail("BATCH_SIZE_MISMATCH", nowMs);
            return false;
        }
        if (batch.endReceived) {
            return batch.expectedQuantity == quantity;
        }
        if (batch.placed > quantity) {
            fail("BATCH_QUANTITY_EXCEEDED", nowMs);
            return false;
        }
        batch.expectedQuantity = quantity;
        batch.endReceived = true;
        if (batch.placed == quantity) {
            finaliseBatch(batch);
        }
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

    public String takeBatchCompletion() {
        return batchCompletions.poll();
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

    public int getBatchBottleCount(String batchId) {
        BatchState batch = batches.get(batchId);
        return batch == null ? 0 : batch.placed;
    }

    public int getBatchPackageCount(String batchId) {
        BatchState batch = batches.get(batchId);
        return batch == null ? 0 : batch.packages;
    }

    public boolean isBatchFinalised(String batchId) {
        BatchState batch = batches.get(batchId);
        return batch != null && batch.finalised;
    }

    public String getStageName() {
        return stage.name();
    }

    public String getActiveBottleId() {
        return activeContext == null ? "-" : activeContext.getBottleId();
    }

    public String getActiveBatchId() {
        return activeBatchId == null ? "-" : activeBatchId;
    }

    public String getFaultReason() {
        return faultReason;
    }

    public String snapshot() {
        return "SortPack[status=" + M4StatusV1.nameOf(status) +
            ",stage=" + stage + ",bottle=" + getActiveBottleId() +
            ",batch=" + getActiveBatchId() +
            ",S=" + smallBottleCount + "/packages=" + smallPackageCount +
            ",L=" + largeBottleCount + "/packages=" + largePackageCount +
            ",fault=" + faultReason + "]";
    }

    public boolean resetFault() {
        if (stage != Stage.FAULT) {
            return false;
        }
        activeContext = null;
        activeBatchId = null;
        stage = Stage.WAITING;
        status = M4StatusV1.READY;
        completion = null;
        faultReason = "-";
        plantCommands.clear();
        lastPlantFeedback = null;
        return true;
    }

    private BatchState batch(String batchId, String sizeCode) {
        BatchState current = batches.get(batchId);
        if (current == null) {
            current = new BatchState(batchId, sizeCode);
            batches.put(batchId, current);
        }
        return current;
    }

    private String expectedLane() {
        return M4BottleContextV1.SMALL.equals(activeContext.getSizeCode()) ?
            "LANE_S" : "LANE_L";
    }

    private void recordPlacement() {
        completedBottleIds.add(activeContext.getBottleId());
        BatchState batch = batches.get(activeBatchId);
        batch.placed++;
        if (M4BottleContextV1.SMALL.equals(activeContext.getSizeCode())) {
            smallBottleCount++;
        }
        else {
            largeBottleCount++;
        }

        int capacity = packageCapacity(batch.sizeCode);
        if (batch.placed % capacity == 0) {
            addPackage(batch);
        }
        if (batch.endReceived && batch.placed == batch.expectedQuantity) {
            finaliseBatch(batch);
        }

        stage = Stage.DONE;
        status = M4StatusV1.DONE;
        completion = activeContext.getBottleId() + "|SORT_PACK_COMPLETE|" +
            activeContext.getPackagingProfileId();
    }

    private void finaliseBatch(BatchState batch) {
        if (batch.finalised || !batch.endReceived ||
            batch.placed != batch.expectedQuantity) {
            return;
        }
        if (batch.placed % packageCapacity(batch.sizeCode) != 0) {
            addPackage(batch);
        }
        batch.finalised = true;
        batchCompletions.add(
            batch.batchId + "|BATCH_PACK_COMPLETE|" + batch.sizeCode +
            "|" + batch.placed + "|" + batch.packages
        );
    }

    private void addPackage(BatchState batch) {
        batch.packages++;
        if (M4BottleContextV1.SMALL.equals(batch.sizeCode)) {
            smallPackageCount++;
        }
        else {
            largePackageCount++;
        }
    }

    private int packageCapacity(String sizeCode) {
        return M4BottleContextV1.SMALL.equals(sizeCode) ?
            smallPackageCapacity : largePackageCapacity;
    }

    /** Unknown bottle formats are isolated as one-bottle batches, never mixed. */
    private static String batchIdForBottle(String bottleId) {
        int marker = bottleId.lastIndexOf("-B");
        if (marker <= 0 || marker + 2 >= bottleId.length()) {
            return bottleId;
        }
        for (int index = marker + 2; index < bottleId.length(); index++) {
            if (!Character.isDigit(bottleId.charAt(index))) {
                return bottleId;
            }
        }
        return bottleId.substring(0, marker);
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
