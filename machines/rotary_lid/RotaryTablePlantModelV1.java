import java.util.HashMap;
import java.util.Map;

/** Six-position Plant model with bottle-correlated station barriers. */
public final class RotaryTablePlantModelV1 {
    public static final int POSITION_COUNT = 6;
    public static final int LOAD_POSITION = 0;
    public static final int FILL_POSITION = 1;
    public static final int LID_POSITION = 2;
    public static final int CAPPER_POSITION = 3;
    public static final int TRANSFER_POSITION = 4;
    public static final int LABEL_POSITION = 5;

    private final BottleStateV1[] positions = new BottleStateV1[POSITION_COUNT];
    private final Map<String, BottleContextV1> contexts =
        new HashMap<String, BottleContextV1>();
    private boolean moving;
    private boolean movementComplete;
    private boolean aligned = true;
    private boolean alignmentFault;
    private boolean triggerLatched;
    private long movementStartMs;
    private long pendingCycleId;
    private long lastCommittedCycleId;
    private long retiredCycleWatermark;
    private int completedSteps;
    private String fillOfferId;
    private String capOfferId;
    private String labelOfferId;
    private final java.util.Set<String> retiredBottleIds =
        new java.util.HashSet<String>();

    public void retireContext(String payload) {
        try { retiredBottleIds.add(BottleContextV1.parse(payload).getBottleId()); }
        catch (IllegalArgumentException ignored) { }
    }

    public void retireBottle(String id) {
        try {
            BottleContextV1.validateBottleId(id);
            retiredBottleIds.add(id);
        }
        catch (IllegalArgumentException ignored) { }
    }

    public boolean registerContext(String payload) {
        BottleContextV1 context;
        try {
            context = BottleContextV1.parse(payload);
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
        if (retiredBottleIds.contains(context.getBottleId())) return false;
        BottleContextV1 existing = contexts.get(context.getBottleId());
        if (existing != null) {
            return existing.encode().equals(context.encode());
        }
        BottleStateV1 activeBottle = findBottle(context.getBottleId());
        if (activeBottle != null &&
            positions[LOAD_POSITION] != activeBottle) {
            return false;
        }
        contexts.put(context.getBottleId(), context);
        if (activeBottle != null) {
            activeBottle.setContext(context);
        }
        return true;
    }

    public boolean loadBottle(String id) {
        try {
            BottleContextV1.validateBottleId(id);
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
        if (retiredBottleIds.contains(id) || moving || movementComplete || positions[LOAD_POSITION] != null ||
            findBottle(id) != null) {
            return false;
        }
        BottleStateV1 bottle = new BottleStateV1(id);
        BottleContextV1 context = contexts.get(id);
        if (context != null) {
            bottle.setContext(context);
        }
        positions[LOAD_POSITION] = bottle;
        return true;
    }

    /** Starts one physical step on a rising command from the M3 Controller. */
    public boolean setMotorCommand(boolean enabled, long cycleId, long nowMs) {
        boolean started = false;
        if (enabled && !triggerLatched && !moving && !movementComplete &&
            cycleId > lastCommittedCycleId && canRotate()) {
            moving = true;
            aligned = false;
            movementStartMs = nowMs;
            pendingCycleId = cycleId;
            started = true;
        }
        triggerLatched = enabled;
        return started;
    }

    /** Completes physical movement but waits for a sensor-confirmed commit. */
    public boolean tick(long nowMs) {
        if (!moving || nowMs - movementStartMs <
            RotaryControllerModelV1.ROTATION_TIME_MS) {
            return false;
        }
        moving = false;
        movementComplete = true;
        aligned = !alignmentFault;
        return true;
    }

    /** Atomically shifts slots after the Controller publishes matching DONE. */
    public boolean commitRotation(long cycleId) {
        if (cycleId <= retiredCycleWatermark) return false;
        // A repeated confirmation acknowledges the same commit without shifting again.
        if (cycleId > 0 && cycleId == lastCommittedCycleId) {
            return true;
        }
        if (!movementComplete || !aligned || cycleId != pendingCycleId ||
            cycleId <= lastCommittedCycleId) {
            return false;
        }
        shiftPositions();
        movementComplete = false;
        lastCommittedCycleId = cycleId;
        completedSteps++;
        clearOfferLatches();
        return true;
    }

    public boolean markFilled(String bottleId) {
        BottleStateV1 bottle = matchingBottle(FILL_POSITION, bottleId);
        if (bottle == null || bottle.isFilled()) {
            return false;
        }
        bottle.markFilled();
        return true;
    }

    public boolean markLidPlaced(String bottleId) {
        BottleStateV1 bottle = matchingBottle(LID_POSITION, bottleId);
        if (bottle == null || bottle.hasLid()) {
            return false;
        }
        try {
            bottle.markLidPlaced();
            return true;
        }
        catch (IllegalStateException exception) {
            return false;
        }
    }

    public boolean markCapped(String bottleId) {
        BottleStateV1 bottle = matchingBottle(CAPPER_POSITION, bottleId);
        if (bottle == null || bottle.isCapped()) {
            return false;
        }
        try {
            bottle.markCapped();
            return true;
        }
        catch (IllegalStateException exception) {
            return false;
        }
    }

    public boolean markLabelled(String bottleId) {
        BottleStateV1 bottle = matchingBottle(LABEL_POSITION, bottleId);
        if (bottle == null || bottle.isLabelled()) {
            return false;
        }
        try {
            bottle.markLabelled();
            return true;
        }
        catch (IllegalStateException exception) {
            return false;
        }
    }

    /** Accepts P6_CLEAR only for the labelled bottle physically at P6. */
    public boolean clearP6(String bottleId) {
        BottleStateV1 bottle = matchingBottle(LABEL_POSITION, bottleId);
        if (bottle == null || !bottle.isLabelled()) {
            return false;
        }
        positions[LABEL_POSITION] = null;
        retiredBottleIds.add(bottleId);
        contexts.remove(bottleId);
        labelOfferId = null;
        return true;
    }

    public boolean canRotate() {
        if (moving || movementComplete || !aligned || !hasAnyBottle() ||
            positions[LABEL_POSITION] != null) {
            return false;
        }
        BottleStateV1 fill = positions[FILL_POSITION];
        BottleStateV1 lid = positions[LID_POSITION];
        BottleStateV1 cap = positions[CAPPER_POSITION];
        BottleStateV1 load = positions[LOAD_POSITION];
        return (load == null || load.getContext() != null) &&
            (fill == null || fill.isFilled()) &&
            (lid == null || lid.hasLid()) &&
            (cap == null || cap.isCapped());
    }

    public String getBottleWaitingForLidId() {
        BottleStateV1 bottle = positions[LID_POSITION];
        if (bottle == null || !bottle.isFilled() || bottle.hasLid()) {
            return null;
        }
        return bottle.getId();
    }

    public String takeFillOffer() {
        BottleStateV1 bottle = positions[FILL_POSITION];
        if (bottle == null || bottle.getContext() == null || bottle.isFilled() ||
            bottle.getId().equals(fillOfferId)) {
            return null;
        }
        fillOfferId = bottle.getId();
        return bottle.getContext().encode();
    }

    public String takeCapOffer() {
        BottleStateV1 bottle = positions[CAPPER_POSITION];
        if (bottle == null || bottle.getContext() == null ||
            !bottle.hasLid() || bottle.isCapped() ||
            bottle.getId().equals(capOfferId)) {
            return null;
        }
        capOfferId = bottle.getId();
        return bottle.getContext().encode();
    }

    public String takeLabelOffer() {
        BottleStateV1 bottle = positions[LABEL_POSITION];
        if (bottle == null || !bottle.isCapped() || bottle.isLabelled() ||
            bottle.getId().equals(labelOfferId)) {
            return null;
        }
        labelOfferId = bottle.getId();
        return bottle.getId();
    }

    public void setAlignmentFault(boolean active) {
        alignmentFault = active;
        if (!active && !moving && movementComplete) {
            aligned = true;
        }
    }

    public boolean isAligned() {
        return aligned;
    }

    public boolean isMoving() {
        return moving;
    }

    public boolean isMovementComplete() {
        return movementComplete;
    }

    public BottleStateV1 getBottleAt(int zeroBasedPosition) {
        if (zeroBasedPosition < 0 || zeroBasedPosition >= POSITION_COUNT) {
            throw new IllegalArgumentException("position must be 0-5");
        }
        return positions[zeroBasedPosition];
    }

    public int getCompletedSteps() {
        return completedSteps;
    }

    public long getLastCommittedCycleId() {
        return lastCommittedCycleId;
    }

    public String positionLabel(int zeroBasedPosition) {
        BottleStateV1 bottle = getBottleAt(zeroBasedPosition);
        return bottle == null ? "empty" : bottle.shortState();
    }

    public String snapshot() {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < positions.length; i++) {
            if (i > 0) {
                result.append('|');
            }
            result.append('P').append(i + 1).append('=')
                .append(positionLabel(i));
        }
        return result.toString();
    }

    private BottleStateV1 matchingBottle(int position, String bottleId) {
        if (bottleId == null) {
            return null;
        }
        BottleStateV1 bottle = positions[position];
        return bottle != null && bottleId.equals(bottle.getId()) ? bottle : null;
    }

    private BottleStateV1 findBottle(String bottleId) {
        for (BottleStateV1 bottle : positions) {
            if (bottle != null && bottle.getId().equals(bottleId)) {
                return bottle;
            }
        }
        return null;
    }

    private boolean hasAnyBottle() {
        for (BottleStateV1 bottle : positions) {
            if (bottle != null) {
                return true;
            }
        }
        return false;
    }

    private void clearOfferLatches() {
        fillOfferId = null;
        capOfferId = null;
        labelOfferId = null;
    }

    private void shiftPositions() {
        if (positions[LABEL_POSITION] != null) {
            throw new IllegalStateException("P6 must be clear before rotation");
        }
        for (int i = POSITION_COUNT - 1; i > 0; i--) {
            positions[i] = positions[i - 1];
        }
        positions[LOAD_POSITION] = null;
    }

    /** Stop the simulated motor before allowing bottle reconciliation. */
    public void stopForSystemReset() {
        moving = false;
        triggerLatched = false;
        lastCommittedCycleId = Math.max(lastCommittedCycleId, pendingCycleId);
        retiredCycleWatermark = Math.max(retiredCycleWatermark, lastCommittedCycleId);
        movementComplete = false;
    }

    public String reconcileSimulatedReset(String resetId) {
        if (moving) return null;
        String before = snapshot();
        retiredBottleIds.addAll(contexts.keySet());
        int removed = 0;
        for (int i = 0; i < positions.length; i++) {
            if (positions[i] != null) {
                retiredBottleIds.add(positions[i].getId());
                removed++;
                positions[i] = null;
            }
        }
        contexts.clear();
        clearOfferLatches();
        pendingCycleId = 0;
        completedSteps = 0;
        alignmentFault = false;
        aligned = true;
        return "SIMULATED_REMOVAL_CONFIRMED count=" + removed + " before=" + before;
    }
}
