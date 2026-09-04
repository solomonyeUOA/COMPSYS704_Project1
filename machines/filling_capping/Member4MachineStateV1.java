/** Thread-safe shared Controller state for the M4 SystemJ clock domains. */
public final class Member4MachineStateV1 {
    private static BottleContextRegistryModelV1 registry;
    private static FillerControllerModelV1 fillerA;
    private static FillerControllerModelV1 fillerB;
    private static CapperControllerModelV1 capper;
    private static SortPackControllerModelV1 sortPack;
    private static M4BoundedEventV1 fillADoneEvent;
    private static M4BoundedEventV1 markFilledEvent;
    private static M4BoundedEventV1 markCappedEvent;
    private static M4BoundedEventV1 fillerACommandEvent;
    private static M4BoundedEventV1 fillerBCommandEvent;
    private static M4BoundedEventV1 capperCommandEvent;
    private static M4BoundedEventV1 sortPackCommandEvent;
    private static M4BoundedEventV1 rotaryContextEvent;
    private static M4BoundedEventV1 loadProfileEvent;
    private static M4BoundedEventV1 unloadProfileEvent;

    static {
        reset();
    }

    private Member4MachineStateV1() {
    }

    public static synchronized void reset() {
        int tolerance = integerProperty("m4.toleranceMl", 0, 0);
        int overflowMargin = integerProperty("m4.overflowMarginMl", 0, 0);
        long timeout = longProperty("m4.operationTimeoutMs", 2500L, 1L);
        int smallCapacity = integerProperty(
            "m4.sortpack.smallPackageCapacity", 2, 1
        );
        int largeCapacity = integerProperty(
            "m4.sortpack.largePackageCapacity", 2, 1
        );
        registry = new BottleContextRegistryModelV1();
        fillerA = new FillerControllerModelV1(
            FillerControllerModelV1.LIQUID_A, tolerance, overflowMargin,
            timeout
        );
        fillerB = new FillerControllerModelV1(
            FillerControllerModelV1.LIQUID_B, tolerance, overflowMargin,
            timeout
        );
        capper = new CapperControllerModelV1(timeout);
        sortPack = new SortPackControllerModelV1(
            smallCapacity, largeCapacity, timeout
        );
        fillADoneEvent = new M4BoundedEventV1(5, 50L);
        markFilledEvent = new M4BoundedEventV1(5, 50L);
        markCappedEvent = new M4BoundedEventV1(5, 50L);
        fillerACommandEvent = new M4BoundedEventV1(3, 30L);
        fillerBCommandEvent = new M4BoundedEventV1(3, 30L);
        capperCommandEvent = new M4BoundedEventV1(3, 30L);
        sortPackCommandEvent = new M4BoundedEventV1(3, 30L);
        rotaryContextEvent = new M4BoundedEventV1(10, 50L);
        loadProfileEvent = new M4BoundedEventV1(10, 50L);
        unloadProfileEvent = new M4BoundedEventV1(10, 50L);
    }

    public static synchronized boolean acceptRecognition(String payload) {
        try {
            String context = registry.acceptRecognition(payload);
            if (context == null) {
                return false;
            }
            long now = System.currentTimeMillis();
            rotaryContextEvent.publish(context, now);
            loadProfileEvent.publish(context, now);
            unloadProfileEvent.publish(context, now);
            return true;
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static synchronized String takeRotaryContext() {
        return rotaryContextEvent.take(System.currentTimeMillis());
    }

    public static synchronized String takeLoadProfile() {
        return loadProfileEvent.take(System.currentTimeMillis());
    }

    public static synchronized String takeUnloadProfile() {
        return unloadProfileEvent.take(System.currentTimeMillis());
    }

    /**
     * Simulation pacing only: the matching context's three local transport
     * windows have drained. This is not a delivery ACK from M2 or M3.
     */
    public static synchronized boolean isContextDistributionComplete(
        String bottleId, String sizeCode
    ) {
        M4BottleContextV1 context = registry.get(bottleId);
        return context != null && sizeCode.equals(context.getSizeCode()) &&
            !rotaryContextEvent.isPending() &&
            !loadProfileEvent.isPending() &&
            !unloadProfileEvent.isPending();
    }

    public static synchronized void setFillerARatio(int ratio) {
        fillerA.setRatio(ratio);
    }

    public static synchronized void setFillerBRatio(int ratio) {
        fillerB.setRatio(ratio);
    }

    public static synchronized void acceptBottleAtFill(String context) {
        fillerA.acceptBottleAtFill(context, System.currentTimeMillis());
    }

    public static synchronized void acceptFillADone(String completion) {
        fillerB.acceptFillADone(completion, System.currentTimeMillis());
    }

    public static synchronized void acceptBottleAtCap(String context) {
        capper.acceptBottleAtCap(context, System.currentTimeMillis());
    }

    public static synchronized void acceptBottleReadyForSort(String context) {
        sortPack.acceptBottleReady(context, System.currentTimeMillis());
    }

    public static synchronized void acceptFillerAFeedback(String feedback) {
        fillerA.acceptPlantFeedback(feedback, System.currentTimeMillis());
    }

    public static synchronized void acceptFillerBFeedback(String feedback) {
        fillerB.acceptPlantFeedback(feedback, System.currentTimeMillis());
    }

    public static synchronized void acceptCapperFeedback(String feedback) {
        capper.acceptPlantFeedback(feedback, System.currentTimeMillis());
    }

    public static synchronized void acceptSortPackFeedback(String feedback) {
        sortPack.acceptPlantFeedback(feedback, System.currentTimeMillis());
    }

    public static synchronized void tick() {
        long now = System.currentTimeMillis();
        fillerA.tick(now);
        fillerB.tick(now);
        capper.tick(now);
        sortPack.tick(now);
    }

    public static synchronized String takeFillerACommand() {
        return takeCommand(fillerA.takePlantCommand(), fillerACommandEvent);
    }

    public static synchronized String takeFillerBCommand() {
        return takeCommand(fillerB.takePlantCommand(), fillerBCommandEvent);
    }

    public static synchronized String takeCapperCommand() {
        return takeCommand(capper.takePlantCommand(), capperCommandEvent);
    }

    public static synchronized String takeSortPackCommand() {
        return takeCommand(
            sortPack.takePlantCommand(),
            sortPackCommandEvent
        );
    }

    public static synchronized String takeFillADone() {
        long now = System.currentTimeMillis();
        String completed = fillerA.takeCompletion();
        if (completed != null) {
            fillADoneEvent.publish(completed, now);
        }
        return fillADoneEvent.take(now);
    }

    public static synchronized String takeMarkFilled() {
        long now = System.currentTimeMillis();
        String completed = fillerB.takeCompletion();
        if (completed != null) {
            markFilledEvent.publish(completed, now);
        }
        return markFilledEvent.take(now);
    }

    public static synchronized String takeMarkCapped() {
        long now = System.currentTimeMillis();
        String completed = capper.takeCompletion();
        if (completed != null) {
            markCappedEvent.publish(completed, now);
        }
        return markCappedEvent.take(now);
    }

    public static synchronized String takeSortPackCompletion() {
        return sortPack.takeCompletion();
    }

    public static synchronized int getFillerAStatus() {
        return fillerA.getStatus();
    }

    public static synchronized int getFillerBStatus() {
        return fillerB.getStatus();
    }

    public static synchronized int getCapperStatus() {
        return capper.getStatus();
    }

    public static synchronized String snapshot() {
        return fillerA.snapshot() + "\n" + fillerB.snapshot() + "\n" +
            capper.snapshot() + "\n" + sortPack.snapshot();
    }

    public static synchronized String fillerASnapshot() {
        return fillerA.snapshot();
    }

    public static synchronized String fillerBSnapshot() {
        return fillerB.snapshot();
    }

    public static synchronized String capperSnapshot() {
        return capper.snapshot();
    }

    public static synchronized String sortPackSnapshot() {
        return sortPack.snapshot();
    }

    private static String takeCommand(
        String nextCommand,
        M4BoundedEventV1 event
    ) {
        long now = System.currentTimeMillis();
        if (nextCommand != null) {
            event.publish(nextCommand, now);
        }
        return event.take(now);
    }

    private static int integerProperty(
        String name,
        int defaultValue,
        int minimum
    ) {
        String configured = System.getProperty(name);
        if (configured == null) {
            return defaultValue;
        }
        try {
            return Math.max(minimum, Integer.parseInt(configured));
        }
        catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static long longProperty(
        String name,
        long defaultValue,
        long minimum
    ) {
        String configured = System.getProperty(name);
        if (configured == null) {
            return defaultValue;
        }
        try {
            return Math.max(minimum, Long.parseLong(configured));
        }
        catch (NumberFormatException exception) {
            return defaultValue;
        }
    }
}
