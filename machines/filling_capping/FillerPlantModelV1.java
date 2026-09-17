import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/** Simulated filler Plant with explicit geometry, valve and refill state. */
public final class FillerPlantModelV1 {
    private enum Stage {
        IDLE,
        POSITIONING,
        POSITIONED,
        DOSING,
        DOSED,
        REFILLING,
        REFILLED,
        SAFE,
        FAULT
    }

    private final long geometryDelayMs;
    private final long doseDelayMs;
    private final long refillDelayMs;
    private final int shutoffLeadMl;
    private final Queue<String> feedback = new ArrayDeque<String>();
    private final Set<String> acceptedCommands = new HashSet<String>();
    private final Set<String> retiredBottleIds = new HashSet<String>();

    private Stage stage = Stage.IDLE;
    private String activeBottleId;
    private String geometryProfile = "-";
    private String nozzleSetpoint = "-";
    private boolean positionConfirmed;
    private int targetMl;
    private int commandedShutoffMl;
    private int measuredMl;
    private long stageStartMs;
    private boolean injectorOpen;
    private boolean inletOpen;
    private boolean doseUnitMoving;
    private boolean forceGeometryFault;
    private boolean forceDoseTimeout;
    private boolean forceRefillTimeout;
    private boolean forceSensorConflict;
    private int forcedOverflowMl;
    private String lastAcceptedCommand;

    public FillerPlantModelV1(
        long geometryDelayMs,
        long doseDelayMs,
        long refillDelayMs
    ) {
        this(geometryDelayMs, doseDelayMs, refillDelayMs, 0);
    }

    public FillerPlantModelV1(
        long geometryDelayMs,
        long doseDelayMs,
        long refillDelayMs,
        int shutoffLeadMl
    ) {
        if (geometryDelayMs < 0 || doseDelayMs < 0 || refillDelayMs < 0 ||
            shutoffLeadMl < 0) {
            throw new IllegalArgumentException("negative Plant delay");
        }
        this.geometryDelayMs = geometryDelayMs;
        this.doseDelayMs = doseDelayMs;
        this.refillDelayMs = refillDelayMs;
        this.shutoffLeadMl = shutoffLeadMl;
    }

    public boolean acceptCommand(String payload, long nowMs) {
        String[] fields;
        try {
            fields = M4ProtocolV1.fields(payload, 3);
            M4ProtocolV1.validateBottleId(fields[0]);
        }
        catch (IllegalArgumentException exception) {
            enterFault("UNKNOWN", "MALFORMED_COMMAND");
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
            safeOutputs();
            positionConfirmed = false;
            activeBottleId = bottleId;
            stage = Stage.FAULT;
            remember(payload);
            return true;
        }
        if (activeBottleId != null && !activeBottleId.equals(bottleId) &&
            stage != Stage.SAFE && stage != Stage.IDLE) {
            enterFault(bottleId, "PLANT_IDENTITY_MISMATCH");
            return false;
        }
        if ("SET_GEOMETRY".equals(action)) {
            final M4GeometryProfileV1 profile;
            try {
                profile = M4GeometryProfileV1.forId(value);
            }
            catch (IllegalArgumentException exception) {
                enterFault(bottleId, "UNKNOWN_GEOMETRY");
                return false;
            }
            safeOutputs();
            retirePreviousBottle(bottleId);
            activeBottleId = bottleId;
            geometryProfile = value;
            nozzleSetpoint = profile.getFillerNozzleZ();
            positionConfirmed = false;
            stage = Stage.POSITIONING;
            stageStartMs = nowMs;
            remember(payload);
            return true;
        }
        if ("START_DOSE".equals(action) && stage == Stage.POSITIONED &&
            positionConfirmed) {
            if (forceSensorConflict) {
                enterFault(bottleId, "SENSOR_CONFLICT");
                return false;
            }
            try {
                targetMl = M4ProtocolV1.unsignedInteger(value, "targetMl");
            }
            catch (IllegalArgumentException exception) {
                enterFault(bottleId, "INVALID_TARGET");
                return false;
            }
            commandedShutoffMl = Math.max(0, targetMl - shutoffLeadMl);
            injectorOpen = true;
            inletOpen = false;
            doseUnitMoving = true;
            stage = Stage.DOSING;
            stageStartMs = nowMs;
            remember(payload);
            return true;
        }
        if ("START_REFILL".equals(action) && stage == Stage.DOSED) {
            // Safety order is deliberate: close injector, then open inlet.
            injectorOpen = false;
            doseUnitMoving = true;
            inletOpen = true;
            stage = Stage.REFILLING;
            stageStartMs = nowMs;
            remember(payload);
            return true;
        }
        if ("FINISH".equals(action) && stage == Stage.REFILLED) {
            safeOutputs();
            stage = Stage.SAFE;
            feedback.add(activeBottleId + "|SAFE|-" );
            remember(payload);
            return true;
        }
        enterFault(bottleId, "UNEXPECTED_COMMAND");
        return false;
    }

    public void tick(long nowMs) {
        if (stage == Stage.POSITIONING &&
            nowMs - stageStartMs >= geometryDelayMs) {
            if (forceGeometryFault) {
                enterFault(activeBottleId, "POSITION_TIMEOUT");
            }
            else {
                stage = Stage.POSITIONED;
                positionConfirmed = true;
                feedback.add(
                    activeBottleId + "|PROFILE_CONFIRMED|" + geometryProfile
                );
            }
        }
        else if (stage == Stage.DOSING && !forceDoseTimeout &&
            nowMs - stageStartMs >= doseDelayMs) {
            int calibratedLead = Math.min(shutoffLeadMl, targetMl);
            measuredMl = commandedShutoffMl + calibratedLead +
                forcedOverflowMl;
            doseUnitMoving = false;
            stage = Stage.DOSED;
            feedback.add(activeBottleId + "|DOSE_DONE|" + measuredMl);
        }
        else if (stage == Stage.REFILLING && !forceRefillTimeout &&
            nowMs - stageStartMs >= refillDelayMs) {
            doseUnitMoving = false;
            stage = Stage.REFILLED;
            feedback.add(activeBottleId + "|REFILL_DONE|-" );
        }
        if (injectorOpen && inletOpen) {
            enterFault(activeBottleId, "VALVE_INTERLOCK");
        }
    }

    public String takeFeedback() {
        return feedback.poll();
    }

    public boolean isInjectorOpen() {
        return injectorOpen;
    }

    public boolean isInletOpen() {
        return inletOpen;
    }

    public boolean isDoseUnitMoving() {
        return doseUnitMoving;
    }

    public int getMeasuredMl() {
        return measuredMl;
    }

    public int getCommandedShutoffMl() {
        return commandedShutoffMl;
    }

    public String getGeometryProfile() {
        return geometryProfile;
    }

    public String getNozzleSetpoint() {
        return nozzleSetpoint;
    }

    public boolean isPositionConfirmed() {
        return positionConfirmed;
    }

    public String getStageName() {
        return stage.name();
    }

    public String snapshot() {
        return "Plant[stage=" + stage + ",bottle=" + activeBottleId +
            ",geometry=" + geometryProfile + ",nozzleZ=" + nozzleSetpoint +
            ",positionConfirmed=" + positionConfirmed +
            ",injector=" + injectorOpen +
            ",inlet=" + inletOpen + ",moving=" + doseUnitMoving +
            ",shutoff=" + commandedShutoffMl + ",volume=" + measuredMl +
            "]";
    }

    public void setForceGeometryFault(boolean active) {
        forceGeometryFault = active;
    }

    public void setForceDoseTimeout(boolean active) {
        forceDoseTimeout = active;
    }

    public void setForceRefillTimeout(boolean active) {
        forceRefillTimeout = active;
    }

    public void setForceSensorConflict(boolean active) {
        forceSensorConflict = active;
    }

    public void setForcedOverflowMl(int amount) {
        forcedOverflowMl = Math.max(0, amount);
    }

    public void clearFaults() {
        forceGeometryFault = false;
        forceDoseTimeout = false;
        forceRefillTimeout = false;
        forceSensorConflict = false;
        forcedOverflowMl = 0;
        safeOutputs();
        stage = Stage.IDLE;
        activeBottleId = null;
        geometryProfile = "-";
        nozzleSetpoint = "-";
        positionConfirmed = false;
        commandedShutoffMl = 0;
        measuredMl = 0;
        targetMl = 0;
        stageStartMs = 0L;
        feedback.clear();
        lastAcceptedCommand = null;
        acceptedCommands.clear();
        retiredBottleIds.clear();
    }

    /** Close both valves and stop dose motion before clearing the cycle. */
    public void resetForSystem() {
        safeOutputs();
        clearFaults();
    }

    public boolean isSystemResetSafe() {
        return !injectorOpen && !inletOpen && !doseUnitMoving &&
            stage == Stage.IDLE && feedback.isEmpty();
    }

    private void enterFault(String bottleId, String reason) {
        safeOutputs();
        positionConfirmed = false;
        stage = Stage.FAULT;
        feedback.add(bottleId + "|FAULT|" + reason);
    }

    private void safeOutputs() {
        injectorOpen = false;
        inletOpen = false;
        doseUnitMoving = false;
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
}
