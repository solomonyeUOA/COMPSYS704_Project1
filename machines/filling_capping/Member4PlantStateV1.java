/** Thread-safe shared Plant state for the M4 SystemJ clock domains. */
public final class Member4PlantStateV1 {
    private static FillerPlantModelV1 fillerA;
    private static FillerPlantModelV1 fillerB;
    private static CapperPlantModelV1 capper;
    private static SortPackPlantModelV1 sortPack;
    private static M4BoundedEventV1 fillerAFeedbackEvent;
    private static M4BoundedEventV1 fillerBFeedbackEvent;
    private static M4BoundedEventV1 capperFeedbackEvent;
    private static M4BoundedEventV1 sortPackFeedbackEvent;

    static {
        reset();
    }

    private Member4PlantStateV1() {
    }

    public static synchronized void reset() {
        long shortDelay = delayProperty("m4.plant.shortDelayMs", 100L);
        long doseDelay = delayProperty("m4.plant.doseDelayMs", 250L);
        long refillDelay = delayProperty("m4.plant.refillDelayMs", 150L);
        int shutoffLead = integerProperty("m4.shutoffLeadMl", 0);
        fillerA = new FillerPlantModelV1(
            shortDelay, doseDelay, refillDelay, shutoffLead
        );
        fillerB = new FillerPlantModelV1(
            shortDelay, doseDelay, refillDelay, shutoffLead
        );
        capper = new CapperPlantModelV1(shortDelay);
        sortPack = new SortPackPlantModelV1(shortDelay, shortDelay);
        fillerAFeedbackEvent = new M4BoundedEventV1(3, 30L);
        fillerBFeedbackEvent = new M4BoundedEventV1(3, 30L);
        capperFeedbackEvent = new M4BoundedEventV1(3, 30L);
        sortPackFeedbackEvent = new M4BoundedEventV1(3, 30L);
    }

    public static synchronized void acceptFillerACommand(String command) {
        if (!M4ResetFenceV1.accept(command)) { return; }
        fillerA.acceptCommand(command, System.currentTimeMillis());
    }

    public static synchronized void acceptFillerBCommand(String command) {
        if (!M4ResetFenceV1.accept(command)) { return; }
        fillerB.acceptCommand(command, System.currentTimeMillis());
    }

    public static synchronized void acceptCapperCommand(String command) {
        if (!M4ResetFenceV1.accept(command)) { return; }
        capper.acceptCommand(command, System.currentTimeMillis());
    }

    public static synchronized void acceptSortPackCommand(String command) {
        if (!M4ResetFenceV1.accept(command)) { return; }
        sortPack.acceptCommand(command, System.currentTimeMillis());
    }

    public static synchronized void tick() {
        if (M4ResetFenceV1.isQuarantined()) {
            tickSystemReset(System.currentTimeMillis());
            return;
        }
        long now = System.currentTimeMillis();
        fillerA.tick(now);
        fillerB.tick(now);
        capper.tick(now);
        sortPack.tick(now);
    }

    public static synchronized String takeFillerAFeedback() {
        if (M4ResetFenceV1.isQuarantined()) { return null; }
        return takeFeedback(fillerA.takeFeedback(), fillerAFeedbackEvent);
    }

    public static synchronized String takeFillerBFeedback() {
        if (M4ResetFenceV1.isQuarantined()) { return null; }
        return takeFeedback(fillerB.takeFeedback(), fillerBFeedbackEvent);
    }

    public static synchronized String takeCapperFeedback() {
        if (M4ResetFenceV1.isQuarantined()) { return null; }
        return takeFeedback(capper.takeFeedback(), capperFeedbackEvent);
    }

    public static synchronized String takeSortPackFeedback() {
        if (M4ResetFenceV1.isQuarantined()) { return null; }
        return takeFeedback(
            sortPack.takeFeedback(),
            sortPackFeedbackEvent
        );
    }

    public static synchronized void injectFillerAFault(String fault) {
        if (M4ResetFenceV1.isQuarantined()) { return; }
        configureFillerFault(fillerA, fault);
    }

    public static synchronized void injectFillerBFault(String fault) {
        if (M4ResetFenceV1.isQuarantined()) { return; }
        configureFillerFault(fillerB, fault);
    }

    public static synchronized void injectCapperFault(String action) {
        if (M4ResetFenceV1.isQuarantined()) { return; }
        capper.setForcedFaultAction(action);
    }

    public static synchronized void injectSortPackFault(String fault) {
        if (M4ResetFenceV1.isQuarantined()) { return; }
        if ("WRONG_LANE".equals(fault)) {
            sortPack.setForceWrongLane(true);
        }
        else if ("PLACEMENT_TIMEOUT".equals(fault)) {
            sortPack.setForcePlacementTimeout(true);
        }
        else if ("PACKAGE_UNAVAILABLE".equals(fault)) {
            sortPack.setPackagePresent(false);
        }
    }

    public static synchronized String snapshot() {
        return "FillerA " + fillerA.snapshot() + "\nFillerB " +
            fillerB.snapshot() + "\n" + capper.snapshot() + "\n" +
            sortPack.snapshot();
    }

    public static synchronized void beginSystemReset(long now) {
        fillerA.resetForSystem();
        fillerB.resetForSystem();
        capper.beginSystemReset(now);
        sortPack.resetForSystem();
        fillerAFeedbackEvent.cancel(); fillerBFeedbackEvent.cancel();
        capperFeedbackEvent.cancel(); sortPackFeedbackEvent.cancel();
    }

    public static synchronized void tickSystemReset(long now) {
        capper.tickSystemReset(now);
    }

    public static synchronized boolean isSystemResetSafe() {
        return fillerA.isSystemResetSafe() && fillerB.isSystemResetSafe() &&
            capper.isSystemResetSafe() && sortPack.isSystemResetSafe();
    }

    public static synchronized String systemResetEvidence() {
        return "FILLER_VALVES_OFF,MOVEMENT_OFF,SORT_STOPPED," +
            capper.systemResetEvidence();
    }

    private static String takeFeedback(
        String nextFeedback,
        M4BoundedEventV1 event
    ) {
        long now = System.currentTimeMillis();
        if (nextFeedback != null) {
            event.publish(nextFeedback, now);
        }
        return event.take(now);
    }

    private static void configureFillerFault(
        FillerPlantModelV1 plant,
        String fault
    ) {
        if ("POSITION_TIMEOUT".equals(fault)) {
            plant.setForceGeometryFault(true);
        }
        else if ("DOSE_TIMEOUT".equals(fault)) {
            plant.setForceDoseTimeout(true);
        }
        else if ("REFILL_TIMEOUT".equals(fault)) {
            plant.setForceRefillTimeout(true);
        }
        else if ("SENSOR_CONFLICT".equals(fault)) {
            plant.setForceSensorConflict(true);
        }
        else if ("OVERFLOW".equals(fault)) {
            plant.setForcedOverflowMl(1);
        }
    }

    private static long delayProperty(String name, long defaultValue) {
        String configured = System.getProperty(name);
        if (configured == null) {
            return defaultValue;
        }
        try {
            return Math.max(0L, Long.parseLong(configured));
        }
        catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static int integerProperty(String name, int defaultValue) {
        String configured = System.getProperty(name);
        if (configured == null) {
            return defaultValue;
        }
        try {
            return Math.max(0, Integer.parseInt(configured));
        }
        catch (NumberFormatException exception) {
            return defaultValue;
        }
    }
}
