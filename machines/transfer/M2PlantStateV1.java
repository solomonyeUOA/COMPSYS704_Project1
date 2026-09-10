/** High-level sensor/actuator simulation for the four M2 Plant Clock Domains. */
public final class M2PlantStateV1 {
    private static final long DEFAULT_ACTION_MILLIS = 100L;

    private static String loaderBottleId;
    private static long loaderStartedAt;
    private static boolean loaderConfirmationPending;

    private static String conveyorBottleId;
    private static boolean conveyorMotorEnabled;
    private static long conveyorMotorStartedAt;
    private static boolean conveyorP1Present;
    private static boolean conveyorArrivalFault;

    private static String labelCommand;
    private static long labelStartedAt;
    private static boolean labelVerificationPending;
    private static boolean labelVerificationFault;

    private static String unloaderBottleId;
    private static long unloadStartedAt;
    private static boolean removalPending;
    private static boolean removalFault;

    private M2PlantStateV1() {
    }

    public static synchronized void reset() {
        loaderBottleId = null;
        loaderStartedAt = 0;
        loaderConfirmationPending = false;
        conveyorBottleId = null;
        conveyorMotorEnabled = false;
        conveyorMotorStartedAt = 0;
        conveyorP1Present = false;
        conveyorArrivalFault = false;
        labelCommand = null;
        labelStartedAt = 0;
        labelVerificationPending = false;
        labelVerificationFault = false;
        unloaderBottleId = null;
        unloadStartedAt = 0;
        removalPending = false;
        removalFault = false;
    }

    public static synchronized boolean commandLoad(
        String bottleId,
        long nowMillis
    ) {
        if (loaderBottleId != null) {
            return loaderBottleId.equals(bottleId);
        }
        M2BottleContextV1.validateToken(bottleId, "bottleId");
        loaderBottleId = bottleId;
        loaderStartedAt = nowMillis;
        return true;
    }

    public static synchronized void tickLoader(long nowMillis) {
        if (loaderBottleId != null &&
            nowMillis - loaderStartedAt >= DEFAULT_ACTION_MILLIS) {
            loaderConfirmationPending = true;
        }
    }

    public static synchronized String takeLoadConfirmed() {
        if (!loaderConfirmationPending) {
            return null;
        }
        String result = loaderBottleId;
        loaderBottleId = null;
        loaderConfirmationPending = false;
        return result;
    }

    public static synchronized boolean registerConveyorBottle(
        String bottleId
    ) {
        M2BottleContextV1.validateToken(bottleId, "bottleId");
        if (conveyorBottleId != null) {
            return conveyorBottleId.equals(bottleId);
        }
        conveyorBottleId = bottleId;
        conveyorP1Present = false;
        return true;
    }

    public static synchronized void setConveyorMotor(
        boolean enabled,
        long nowMillis
    ) {
        if (enabled && !conveyorMotorEnabled) {
            conveyorMotorStartedAt = nowMillis;
        }
        conveyorMotorEnabled = enabled;
    }

    public static synchronized void tickConveyor(long nowMillis) {
        if (conveyorBottleId != null && conveyorMotorEnabled &&
            !conveyorArrivalFault &&
            nowMillis - conveyorMotorStartedAt >= DEFAULT_ACTION_MILLIS) {
            conveyorP1Present = true;
        }
    }

    public static synchronized String conveyorFeedback() {
        if (conveyorBottleId == null) {
            return null;
        }
        boolean entryClear = conveyorP1Present;
        boolean motorStopped = !conveyorMotorEnabled;
        return conveyorBottleId + "|" + conveyorP1Present + "|" +
            entryClear + "|" + motorStopped + "|true|true";
    }

    public static synchronized boolean commitConveyorHandoff(
        String bottleId
    ) {
        if (conveyorBottleId == null ||
            !conveyorBottleId.equals(bottleId) || !conveyorP1Present ||
            conveyorMotorEnabled) {
            return false;
        }
        conveyorBottleId = null;
        conveyorP1Present = false;
        return true;
    }

    public static synchronized void setConveyorArrivalFault(boolean active) {
        conveyorArrivalFault = active;
    }

    public static synchronized boolean commandLabel(
        String payload,
        long nowMillis
    ) {
        String[] fields = payload == null ? new String[0] :
            payload.split("\\|", -1);
        if (fields.length != 2) {
            return false;
        }
        M2BottleContextV1.validateToken(fields[0], "bottleId");
        M2BottleContextV1.validateToken(fields[1], "labelData");
        if (labelCommand != null) {
            return labelCommand.equals(payload);
        }
        labelCommand = payload;
        labelStartedAt = nowMillis;
        return true;
    }

    public static synchronized void tickLabeller(long nowMillis) {
        if (labelCommand != null &&
            nowMillis - labelStartedAt >= DEFAULT_ACTION_MILLIS) {
            labelVerificationPending = true;
        }
    }

    public static synchronized String takeLabelVerification() {
        if (!labelVerificationPending || labelCommand == null) {
            return null;
        }
        String bottleId = labelCommand.split("\\|", -1)[0];
        String result = bottleId + "|" +
            (labelVerificationFault ? "FAIL" : "PASS");
        labelCommand = null;
        labelVerificationPending = false;
        return result;
    }

    public static synchronized void setLabelVerificationFault(boolean active) {
        labelVerificationFault = active;
    }

    public static synchronized boolean commandUnload(
        String bottleId,
        long nowMillis
    ) {
        M2BottleContextV1.validateToken(bottleId, "bottleId");
        if (unloaderBottleId != null) {
            return unloaderBottleId.equals(bottleId);
        }
        unloaderBottleId = bottleId;
        unloadStartedAt = nowMillis;
        return true;
    }

    public static synchronized void tickUnloader(long nowMillis) {
        if (unloaderBottleId != null && !removalFault &&
            nowMillis - unloadStartedAt >= DEFAULT_ACTION_MILLIS) {
            removalPending = true;
        }
    }

    public static synchronized String takeRemovalConfirmed() {
        if (!removalPending || unloaderBottleId == null) {
            return null;
        }
        String result = unloaderBottleId + "|true";
        unloaderBottleId = null;
        removalPending = false;
        return result;
    }

    public static synchronized void setRemovalFault(boolean active) {
        removalFault = active;
    }
}
