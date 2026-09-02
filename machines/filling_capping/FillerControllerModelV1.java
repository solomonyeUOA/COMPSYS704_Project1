import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/** Bottle-correlated controller model shared by the A and B fillers. */
public final class FillerControllerModelV1 {
    public static final String LIQUID_A = "A";
    public static final String LIQUID_B = "B";

    private enum Stage {
        WAITING,
        POSITIONING,
        DOSING,
        REFILLING,
        SAFE_WAIT,
        DONE,
        FAULT
    }

    private final String liquid;
    private final int toleranceMl;
    private final int overflowMarginMl;
    private final long timeoutMs;
    private final Queue<String> plantCommands = new ArrayDeque<String>();
    private final Set<String> completedBottleIds = new HashSet<String>();

    private int ratio = -1;
    private int status = M4StatusV1.READY;
    private Stage stage = Stage.WAITING;
    private M4BottleContextV1 activeContext;
    private int measuredAMl;
    private int targetMl;
    private int measuredMl;
    private long stageStartMs;
    private String completion;
    private String faultReason = "-";
    private String lastPlantFeedback;

    public FillerControllerModelV1(
        String liquid,
        int toleranceMl,
        long timeoutMs
    ) {
        this(liquid, toleranceMl, 0, timeoutMs);
    }

    public FillerControllerModelV1(
        String liquid,
        int toleranceMl,
        int overflowMarginMl,
        long timeoutMs
    ) {
        if ((!LIQUID_A.equals(liquid) && !LIQUID_B.equals(liquid)) ||
            toleranceMl < 0 || overflowMarginMl < 0 || timeoutMs <= 0) {
            throw new IllegalArgumentException("invalid filler configuration");
        }
        this.liquid = liquid;
        this.toleranceMl = toleranceMl;
        this.overflowMarginMl = overflowMarginMl;
        this.timeoutMs = timeoutMs;
    }

    /** Stores batch configuration only; it never starts a physical action. */
    public boolean setRatio(int value) {
        if (value < 0 || value > 100) {
            fail("INVALID_RECIPE", System.currentTimeMillis());
            return false;
        }
        ratio = value;
        return true;
    }

    public boolean acceptBottleAtFill(String payload, long nowMs) {
        if (!LIQUID_A.equals(liquid)) {
            throw new IllegalStateException("only Filler A accepts P2 context");
        }
        M4BottleContextV1 context;
        try {
            context = M4BottleContextV1.parse(payload);
        }
        catch (IllegalArgumentException exception) {
            fail("INVALID_CONTEXT", nowMs);
            return false;
        }
        return start(context, 0, nowMs);
    }

    public boolean acceptFillADone(String payload, long nowMs) {
        if (!LIQUID_B.equals(liquid)) {
            throw new IllegalStateException("only Filler B accepts A completion");
        }
        M4FillADoneV1 done;
        try {
            done = M4FillADoneV1.parse(payload);
        }
        catch (IllegalArgumentException exception) {
            fail("INVALID_FILL_A_DONE", nowMs);
            return false;
        }
        return start(done.getContext(), done.getMeasuredAMl(), nowMs);
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
            "PROFILE_CONFIRMED".equals(event)) {
            if (!activeContext.getGeometryProfileId().equals(value)) {
                fail("GEOMETRY_MISMATCH", nowMs);
                return;
            }
            stage = Stage.DOSING;
            stageStartMs = nowMs;
            plantCommands.add(action("START_DOSE", String.valueOf(targetMl)));
            return;
        }
        if (stage == Stage.DOSING && "DOSE_DONE".equals(event)) {
            int measured;
            try {
                measured = M4ProtocolV1.unsignedInteger(value, "measuredMl");
            }
            catch (IllegalArgumentException exception) {
                fail("INVALID_VOLUME_FEEDBACK", nowMs);
                return;
            }
            int cumulativeMeasured = LIQUID_B.equals(liquid) ?
                measuredAMl + measured : measured;
            if (cumulativeMeasured >
                activeContext.getCapacityMl() + overflowMarginMl) {
                fail("OVERFLOW", nowMs);
                return;
            }
            if (Math.abs(measured - targetMl) > toleranceMl) {
                fail("VOLUME_OUT_OF_TOLERANCE", nowMs);
                return;
            }
            measuredMl = measured;
            stage = Stage.REFILLING;
            stageStartMs = nowMs;
            plantCommands.add(action("START_REFILL", "-"));
            return;
        }
        if (stage == Stage.REFILLING && "REFILL_DONE".equals(event)) {
            stage = Stage.SAFE_WAIT;
            stageStartMs = nowMs;
            plantCommands.add(action("FINISH", "-"));
            return;
        }
        if (stage == Stage.SAFE_WAIT && "SAFE".equals(event)) {
            complete();
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

    /** A returns full context plus measured A; B returns bottleId. */
    public String takeCompletion() {
        String result = completion;
        completion = null;
        return result;
    }

    public int getStatus() {
        return status;
    }

    public int getRatio() {
        return ratio;
    }

    public int getTargetMl() {
        return targetMl;
    }

    public int getMeasuredMl() {
        return measuredMl;
    }

    public String getActiveBottleId() {
        return activeContext == null ? "-" : activeContext.getBottleId();
    }

    public String getStageName() {
        return stage.name();
    }

    public String getFaultReason() {
        return faultReason;
    }

    public String snapshot() {
        return "Filler" + liquid + "[status=" + M4StatusV1.nameOf(status) +
            ",stage=" + stage + ",bottle=" + getActiveBottleId() +
            ",ratio=" + ratio + ",target=" + targetMl +
            ",measured=" + measuredMl + ",fault=" + faultReason + "]";
    }

    public boolean resetFault() {
        if (stage != Stage.FAULT) {
            return false;
        }
        activeContext = null;
        stage = Stage.WAITING;
        status = M4StatusV1.READY;
        faultReason = "-";
        plantCommands.clear();
        completion = null;
        lastPlantFeedback = null;
        return true;
    }

    private boolean start(
        M4BottleContextV1 context,
        int acceptedMeasuredAMl,
        long nowMs
    ) {
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
        if (ratio < 0 || ratio > 100) {
            fail("MISSING_RECIPE", nowMs);
            return false;
        }
        int target = context.targetForRatio(ratio);
        if (LIQUID_B.equals(liquid) &&
            Math.abs(acceptedMeasuredAMl + target -
                context.getCapacityMl()) > toleranceMl) {
            fail("RECIPE_COMPLEMENT_MISMATCH", nowMs);
            return false;
        }
        activeContext = context;
        measuredAMl = acceptedMeasuredAMl;
        targetMl = target;
        measuredMl = 0;
        completion = null;
        faultReason = "-";
        lastPlantFeedback = null;
        stage = Stage.POSITIONING;
        status = M4StatusV1.BUSY;
        stageStartMs = nowMs;
        plantCommands.add(action(
            "SET_GEOMETRY",
            context.getGeometryProfileId()
        ));
        return true;
    }

    private void complete() {
        stage = Stage.DONE;
        status = M4StatusV1.DONE;
        completedBottleIds.add(activeContext.getBottleId());
        if (LIQUID_A.equals(liquid)) {
            completion = new M4FillADoneV1(
                activeContext,
                measuredMl
            ).encode();
        }
        else {
            completion = activeContext.getBottleId();
        }
    }

    private String action(String name, String value) {
        return activeContext.getBottleId() + "|" + name + "|" + value;
    }

    private void fail(String reason, long nowMs) {
        if (stage != Stage.FAULT && activeContext != null) {
            plantCommands.add(action("SAFE_STOP", reason));
        }
        stage = Stage.FAULT;
        status = M4StatusV1.FAULT;
        stageStartMs = nowMs;
        completion = null;
        faultReason = reason == null || reason.isEmpty() ? "FAULT" : reason;
    }
}
