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
        fillerA.acceptCommand(command, System.currentTimeMillis());
    }

    public static synchronized void acceptFillerBCommand(String command) {
        fillerB.acceptCommand(command, System.currentTimeMillis());
    }

    public static synchronized void acceptCapperCommand(String command) {
        capper.acceptCommand(command, System.currentTimeMillis());
    }

    public static synchronized void acceptSortPackCommand(String command) {
        sortPack.acceptCommand(command, System.currentTimeMillis());
    }

    public static synchronized void tick() {
        long now = System.currentTimeMillis();
        fillerA.tick(now);
        fillerB.tick(now);
        capper.tick(now);
        sortPack.tick(now);
    }

    public static synchronized String takeFillerAFeedback() {
        return takeFeedback(fillerA.takeFeedback(), fillerAFeedbackEvent);
    }

    public static synchronized String takeFillerBFeedback() {
        return takeFeedback(fillerB.takeFeedback(), fillerBFeedbackEvent);
    }

    public static synchronized String takeCapperFeedback() {
        return takeFeedback(capper.takeFeedback(), capperFeedbackEvent);
    }

    public static synchronized String takeSortPackFeedback() {
        return takeFeedback(
            sortPack.takeFeedback(),
            sortPackFeedbackEvent
        );
    }

    public static synchronized void injectFillerAFault(String fault) {
        configureFillerFault(fillerA, fault);
    }

    public static synchronized void injectFillerBFault(String fault) {
        configureFillerFault(fillerB, fault);
    }

    public static synchronized void injectCapperFault(String action) {
        capper.setForcedFaultAction(action);
    }

    public static synchronized void injectSortPackFault(String fault) {
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
