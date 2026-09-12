/** High-level sensor/actuator simulation for the four M2 Plant Clock Domains. */
public final class M2PlantStateV1 {
    private static final long DEFAULT_ACTION_MILLIS = SimulationTiming.scaleMillis(100L);

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
    private static String labelVerificationResult;
    private static boolean labelVerificationFault;

    private static String unloaderBottleId;
    private static long unloadStartedAt;
    private static boolean removalPending;
    private static boolean removalFault;
    private static final java.util.Set<String> removedBottleIds =
        new java.util.HashSet<String>();
    private static final java.util.Set<String> loadedBottleIds =
        new java.util.HashSet<String>();
    private static final java.util.Set<String> transferredBottleIds =
        new java.util.HashSet<String>();
    private static final java.util.Map<String, String> verifiedLabelCommands =
        new java.util.HashMap<String, String>();
    private static M2HeldSignalOfferV1 removalConfirmationOffer =
        new M2HeldSignalOfferV1(10, 100L, 25L);
    private static M2HeldSignalOfferV1 loadConfirmationOffer =
        new M2HeldSignalOfferV1(10, 100L, 25L);
    private static M2HeldSignalOfferV1 labelVerificationOffer =
        new M2HeldSignalOfferV1(10, 100L, 25L);

    private M2PlantStateV1() {
    }

    public static synchronized void safeStopForSystemReset() {
        conveyorMotorEnabled = false;
        M2SystemResetStateV1.observeBottle(loaderBottleId);
        M2SystemResetStateV1.observeBottle(conveyorBottleId);
        M2SystemResetStateV1.observeBottle(unloaderBottleId);
        if (labelCommand != null) {
            M2SystemResetStateV1.observeBottle(labelCommand.split("\\|", -1)[0]);
        }
        reset();
    }

    public static synchronized boolean isSafeInitialState() {
        return !conveyorMotorEnabled && loaderBottleId == null &&
            conveyorBottleId == null && labelCommand == null &&
            unloaderBottleId == null && !loaderConfirmationPending &&
            !labelVerificationPending && !removalPending &&
            !removalConfirmationOffer.isActive() &&
            !loadConfirmationOffer.isActive() && !labelVerificationOffer.isActive();
    }

    public static synchronized boolean isConveyorMotorEnabled() {
        return conveyorMotorEnabled;
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
        labelVerificationResult = null;
        labelVerificationFault = false;
        unloaderBottleId = null;
        unloadStartedAt = 0;
        removalPending = false;
        removalFault = false;
        removedBottleIds.clear();
        loadedBottleIds.clear();
        transferredBottleIds.clear();
        verifiedLabelCommands.clear();
        removalConfirmationOffer = new M2HeldSignalOfferV1(10, 100L, 25L);
        loadConfirmationOffer = new M2HeldSignalOfferV1(10, 100L, 25L);
        labelVerificationOffer = new M2HeldSignalOfferV1(10, 100L, 25L);
    }

    public static synchronized boolean commandLoad(
        String bottleId,
        long nowMillis
    ) {
        if (!M2SystemResetStateV1.allowBottle(bottleId)) { return false; }
        if (loaderBottleId != null) {
            return loaderBottleId.equals(bottleId);
        }
        if (loadedBottleIds.contains(bottleId)) { return true; }
        M2BottleContextV1.validateToken(bottleId, "bottleId");
        loaderBottleId = bottleId;
        M2SystemResetStateV1.observeBottle(bottleId);
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
        loadedBottleIds.add(loaderBottleId);
        loaderBottleId = null;
        loaderConfirmationPending = false;
        return result;
    }

    public static synchronized String nextLoadConfirmationOffer() {
        return nextLoadConfirmationOffer(System.currentTimeMillis());
    }

    static synchronized String nextLoadConfirmationOffer(long nowMillis) {
        if (M2SystemResetStateV1.isQuarantined()) { return null; }
        if (!loadConfirmationOffer.isActive()) {
            String bottleId = takeLoadConfirmed();
            if (bottleId != null) {
                loadConfirmationOffer.arm(bottleId, bottleId, nowMillis);
            }
        }
        return loadConfirmationOffer.nextReactionValue(nowMillis);
    }

    public static synchronized boolean acknowledgeLoadConfirmation(String bottleId) {
        return loadConfirmationOffer.acknowledge(bottleId);
    }

    public static synchronized boolean registerConveyorBottle(
        String bottleId
    ) {
        if (!M2SystemResetStateV1.allowBottle(bottleId)) { return false; }
        M2BottleContextV1.validateToken(bottleId, "bottleId");
        if (transferredBottleIds.contains(bottleId)) { return true; }
        if (conveyorBottleId != null) {
            return conveyorBottleId.equals(bottleId);
        }
        conveyorBottleId = bottleId;
        M2SystemResetStateV1.observeBottle(bottleId);
        conveyorP1Present = false;
        return true;
    }

    public static synchronized void setConveyorMotor(
        boolean enabled,
        long nowMillis
    ) {
        if (M2SystemResetStateV1.isQuarantined() || conveyorBottleId == null) {
            conveyorMotorEnabled = false;
            return;
        }
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
        transferredBottleIds.add(conveyorBottleId);
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
        if (!M2SystemResetStateV1.allowBottle(fields[0])) { return false; }
        M2BottleContextV1.validateToken(fields[0], "bottleId");
        M2BottleContextV1.validateToken(fields[1], "labelData");
        String verified = verifiedLabelCommands.get(fields[0]);
        if (verified != null) { return verified.equals(payload); }
        if (labelCommand != null) {
            return labelCommand.equals(payload);
        }
        labelCommand = payload;
        M2SystemResetStateV1.observeBottle(fields[0]);
        labelStartedAt = nowMillis;
        return true;
    }

    public static synchronized void tickLabeller(long nowMillis) {
        if (labelCommand != null && !labelVerificationPending &&
            nowMillis - labelStartedAt >= DEFAULT_ACTION_MILLIS) {
            // Capture the verifier at physical completion, not later when an
            // older unacknowledged feedback offer finally frees the outbox.
            String bottleId = labelCommand.split("\\|", -1)[0];
            labelVerificationResult = bottleId + "|" +
                (labelVerificationFault ? "FAIL" : "PASS");
            labelVerificationPending = true;
        }
    }

    public static synchronized String takeLabelVerification() {
        if (!labelVerificationPending || labelCommand == null) {
            return null;
        }
        String bottleId = labelCommand.split("\\|", -1)[0];
        String result = labelVerificationResult;
        verifiedLabelCommands.put(bottleId, labelCommand);
        labelCommand = null;
        labelVerificationPending = false;
        labelVerificationResult = null;
        return result;
    }

    public static synchronized String nextLabelVerificationOffer() {
        return nextLabelVerificationOffer(System.currentTimeMillis());
    }

    static synchronized String nextLabelVerificationOffer(long nowMillis) {
        if (M2SystemResetStateV1.isQuarantined()) { return null; }
        if (!labelVerificationOffer.isActive()) {
            String evidence = takeLabelVerification();
            if (evidence != null) {
                labelVerificationOffer.arm(evidence.split("\\|", -1)[0], evidence, nowMillis);
            }
        }
        return labelVerificationOffer.nextReactionValue(nowMillis);
    }

    public static synchronized boolean acknowledgeLabelVerification(String bottleId) {
        return labelVerificationOffer.acknowledge(bottleId);
    }

    public static synchronized void setLabelVerificationFault(boolean active) {
        labelVerificationFault = active;
    }

    public static synchronized boolean commandUnload(
        String bottleId,
        long nowMillis
    ) {
        if (!M2SystemResetStateV1.allowBottle(bottleId)) { return false; }
        M2BottleContextV1.validateToken(bottleId, "bottleId");
        if (removedBottleIds.contains(bottleId)) { return true; }
        if (unloaderBottleId != null) {
            return unloaderBottleId.equals(bottleId);
        }
        unloaderBottleId = bottleId;
        M2SystemResetStateV1.observeBottle(bottleId);
        unloadStartedAt = nowMillis;
        System.out.println("[M2-UNLOAD] actuator accepted " + bottleId);
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
        removedBottleIds.add(unloaderBottleId);
        unloaderBottleId = null;
        removalPending = false;
        return result;
    }

    /** Keep sensor evidence across lost network reactions without re-actuating. */
    public static synchronized String nextRemovalConfirmationOffer() {
        return nextRemovalConfirmationOffer(System.currentTimeMillis());
    }

    static synchronized String nextRemovalConfirmationOffer(long nowMillis) {
        if (M2SystemResetStateV1.isQuarantined()) { return null; }
        if (!removalConfirmationOffer.isActive()) {
            String evidence = takeRemovalConfirmed();
            if (evidence != null) {
                String bottleId = evidence.split("\\|", -1)[0];
                removalConfirmationOffer.arm(bottleId, evidence, nowMillis);
                System.out.println("[M2-UNLOAD] sensor confirmed " + bottleId);
            }
        }
        return removalConfirmationOffer.nextReactionValue(nowMillis);
    }

    public static synchronized boolean acknowledgeRemovalConfirmation(String bottleId) {
        return removalConfirmationOffer.acknowledge(bottleId);
    }

    public static synchronized void setRemovalFault(boolean active) {
        removalFault = active;
    }
}
