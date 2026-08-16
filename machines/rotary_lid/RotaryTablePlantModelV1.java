/** Six-position physical Plant model for the rotary table. */
public final class RotaryTablePlantModelV1 {
    public static final int POSITION_COUNT = 6;
    public static final int LOAD_POSITION = 0;
    public static final int FILL_POSITION = 1;
    public static final int LID_POSITION = 2;
    public static final int CAPPER_POSITION = 3;
    public static final int EXIT_POSITION = 4;

    private final BottleStateV1[] positions =
        new BottleStateV1[POSITION_COUNT];
    private boolean moving;
    private boolean aligned = true;
    private boolean alignmentFault;
    private boolean triggerLatched;
    private long movementStartMs;
    private int completedSteps;

    public boolean loadBottle(String id) {
        if (moving || positions[LOAD_POSITION] != null) {
            return false;
        }
        positions[LOAD_POSITION] = new BottleStateV1(id);
        return true;
    }

    /** Starts one physical step on a rising actuator command. */
    public boolean setMotorCommand(boolean enabled, long nowMs) {
        boolean started = false;
        if (enabled && !triggerLatched && !moving) {
            moving = true;
            aligned = false;
            movementStartMs = nowMs;
            started = true;
        }
        triggerLatched = enabled;
        return started;
    }

    /** Returns true exactly when a physical 60-degree step completes. */
    public boolean tick(long nowMs) {
        if (!moving || nowMs - movementStartMs <
            RotaryControllerModelV1.ROTATION_TIME_MS) {
            return false;
        }

        shiftPositions();
        moving = false;
        aligned = !alignmentFault;
        completedSteps++;
        return true;
    }

    public boolean markFilled() {
        BottleStateV1 bottle = positions[FILL_POSITION];
        if (bottle == null) {
            return false;
        }
        bottle.markFilled();
        return true;
    }

    public boolean markLidPlaced() {
        BottleStateV1 bottle = positions[LID_POSITION];
        if (bottle == null) {
            return false;
        }
        bottle.markLidPlaced();
        return true;
    }

    public boolean markCapped() {
        BottleStateV1 bottle = positions[CAPPER_POSITION];
        if (bottle == null) {
            return false;
        }
        bottle.markCapped();
        return true;
    }

    public BottleStateV1 removeBottleAtExit() {
        BottleStateV1 bottle = positions[EXIT_POSITION];
        positions[EXIT_POSITION] = null;
        return bottle;
    }

    public void setAlignmentFault(boolean active) {
        alignmentFault = active;
        if (!active && !moving) {
            aligned = true;
        }
    }

    public boolean isAligned() {
        return aligned;
    }

    public boolean isMoving() {
        return moving;
    }

    public boolean hasCappedBottleAtPosition1() {
        BottleStateV1 bottle = positions[LOAD_POSITION];
        return bottle != null && bottle.isCapped();
    }

    public boolean hasBottleWaitingForLid() {
        BottleStateV1 bottle = positions[LID_POSITION];
        return bottle != null && bottle.isFilled() && !bottle.hasLid();
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

    private void shiftPositions() {
        BottleStateV1 fromUnusedPosition = positions[POSITION_COUNT - 1];
        for (int i = POSITION_COUNT - 1; i > 0; i--) {
            positions[i] = positions[i - 1];
        }
        positions[0] = fromUnusedPosition;
    }
}
