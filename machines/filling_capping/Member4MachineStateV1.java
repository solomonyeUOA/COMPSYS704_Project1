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
    private static final java.util.Queue<String> twinObservations =
        new java.util.ArrayDeque<String>();
    private static final M4BoundedEventV1 twinObservationEvent =
        new M4BoundedEventV1(5, 50L);
    private static long twinSequence;

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
        if (!M4ResetFenceV1.accept(payload)) { return false; }
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
        if (M4ResetFenceV1.isQuarantined()) { return null; }
        return rotaryContextEvent.take(System.currentTimeMillis());
    }

    public static synchronized String takeLoadProfile() {
        if (M4ResetFenceV1.isQuarantined()) { return null; }
        return loadProfileEvent.take(System.currentTimeMillis());
    }

    public static synchronized String takeUnloadProfile() {
        if (M4ResetFenceV1.isQuarantined()) { return null; }
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
        if (M4ResetFenceV1.isQuarantined()) { return; }
        fillerA.setRatio(ratio);
    }

    public static synchronized void setFillerBRatio(int ratio) {
        if (M4ResetFenceV1.isQuarantined()) { return; }
        fillerB.setRatio(ratio);
    }

    public static synchronized boolean acceptBottleAtFill(String context) {
        if (!M4ResetFenceV1.accept(context)) { return false; }
        return fillerA.acceptBottleAtFill(
            context,
            System.currentTimeMillis()
        );
    }

    public static synchronized void acceptFillADone(String completion) {
        if (!M4ResetFenceV1.accept(completion)) { return; }
        fillerB.acceptFillADone(completion, System.currentTimeMillis());
    }

    public static synchronized void acceptBottleAtCap(String context) {
        if (!M4ResetFenceV1.accept(context)) { return; }
        capper.acceptBottleAtCap(context, System.currentTimeMillis());
    }

    public static synchronized boolean acceptBottleReadyForSort(
        String context
    ) {
        if (!M4ResetFenceV1.accept(context)) { return false; }
        return sortPack.acceptBottleReady(
            context,
            System.currentTimeMillis()
        );
    }

    public static synchronized void acceptFillerAFeedback(String feedback) {
        if (!M4ResetFenceV1.accept(feedback)) { return; }
        fillerA.acceptPlantFeedback(feedback, System.currentTimeMillis());
    }

    public static synchronized void acceptFillerBFeedback(String feedback) {
        if (!M4ResetFenceV1.accept(feedback)) { return; }
        fillerB.acceptPlantFeedback(feedback, System.currentTimeMillis());
    }

    public static synchronized void acceptCapperFeedback(String feedback) {
        if (!M4ResetFenceV1.accept(feedback)) { return; }
        capper.acceptPlantFeedback(feedback, System.currentTimeMillis());
    }

    public static synchronized void acceptSortPackFeedback(String feedback) {
        if (!M4ResetFenceV1.accept(feedback)) { return; }
        sortPack.acceptPlantFeedback(feedback, System.currentTimeMillis());
    }

    public static synchronized void tick() {
        if (M4ResetFenceV1.isQuarantined()) { return; }
        long now = System.currentTimeMillis();
        fillerA.tick(now);
        fillerB.tick(now);
        capper.tick(now);
        sortPack.tick(now);
    }

    public static synchronized String takeFillerACommand() {
        if (M4ResetFenceV1.isQuarantined()) { return null; }
        return takeCommand(fillerA.takePlantCommand(), fillerACommandEvent);
    }

    public static synchronized String takeFillerBCommand() {
        if (M4ResetFenceV1.isQuarantined()) { return null; }
        return takeCommand(fillerB.takePlantCommand(), fillerBCommandEvent);
    }

    public static synchronized String takeCapperCommand() {
        if (M4ResetFenceV1.isQuarantined()) { return null; }
        return takeCommand(capper.takePlantCommand(), capperCommandEvent);
    }

    public static synchronized String takeSortPackCommand() {
        if (M4ResetFenceV1.isQuarantined()) { return null; }
        return takeCommand(
            sortPack.takePlantCommand(),
            sortPackCommandEvent
        );
    }

    public static synchronized String takeFillADone() {
        if (M4ResetFenceV1.isQuarantined()) { return null; }
        long now = System.currentTimeMillis();
        String completed = fillerA.takeCompletion();
        if (completed != null) {
            fillADoneEvent.publish(completed, now);
        }
        return fillADoneEvent.take(now);
    }

    public static synchronized String takeMarkFilled() {
        if (M4ResetFenceV1.isQuarantined()) { return null; }
        long now = System.currentTimeMillis();
        String completed = fillerB.takeCompletion();
        if (completed != null) {
            markFilledEvent.publish(completed, now);
            observe(completed, "FILLED", "FILLER_B");
        }
        return markFilledEvent.take(now);
    }

    public static synchronized String takeMarkCapped() {
        if (M4ResetFenceV1.isQuarantined()) { return null; }
        long now = System.currentTimeMillis();
        String completed = capper.takeCompletion();
        if (completed != null) {
            markCappedEvent.publish(completed, now);
            observe(completed, "CAPPED", "CAPPER");
        }
        return markCappedEvent.take(now);
    }

    public static synchronized String takeSortPackCompletion() {
        if (M4ResetFenceV1.isQuarantined()) { return null; }
        String completed = sortPack.takeCompletion();
        if (completed != null) { observe(completed, "SORTED", "SORT_PACK"); }
        return completed;
    }

    private static void observe(String bottle, String stage, String resource) {
        bottle = bottle.split("\\|", -1)[0];
        twinObservations.add("V1|W|M4-E01-" + (++twinSequence) + "|" +
            bottle + "|" + stage + "|" + resource + "|-|" +
            System.currentTimeMillis());
    }

    public static synchronized String takeWorkpieceObservation() {
        return takeWorkpieceObservation(System.currentTimeMillis());
    }

    public static synchronized String takeWorkpieceObservation(long now) {
        if (M4ResetFenceV1.isQuarantined()) { return null; }
        if (!twinObservationEvent.isPending() && !twinObservations.isEmpty()) {
            twinObservationEvent.publish(twinObservations.remove(), now);
        }
        return twinObservationEvent.take(now);
    }

    public static synchronized void beginSystemReset() {
        fillADoneEvent.cancel(); markFilledEvent.cancel();
        markCappedEvent.cancel(); fillerACommandEvent.cancel();
        fillerBCommandEvent.cancel(); capperCommandEvent.cancel();
        sortPackCommandEvent.cancel(); rotaryContextEvent.cancel();
        loadProfileEvent.cancel(); unloadProfileEvent.cancel();
        twinObservations.clear(); twinObservationEvent.cancel();
    }

    public static synchronized void completeSystemReset() {
        BottleContextRegistryModelV1 retainedRegistry = registry;
        retainedRegistry.resetForSystem();
        reset();
        registry = retainedRegistry;
    }

    public static synchronized int contextCount() { return registry.size(); }

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
