import java.util.ArrayDeque;
import java.util.Queue;

/** Shared SystemJ-facing state for all M2 machine Controllers. */
public final class M2MachineStateV1 {
    private static final String SOURCE_EPOCH =
        System.getProperty("m2.sourceEpoch", "E01");

    private static BottleLoaderControllerModelV1 loader;
    private static ConveyorControllerModelV1 conveyor;
    private static LabellerControllerModelV1 labeller;
    private static BottleUnloaderControllerModelV1 unloader;
    private static M2BoundedSignalOfferV1 bottleAtConveyorOffer;
    private static M2BoundedSignalOfferV1 loadBottleOffer;
    private static M2BoundedSignalOfferV1 markLabelledOffer;
    private static M2BoundedSignalOfferV1 unloadReadyOffer;
    private static M2BoundedSignalOfferV1 p6ClearOffer;
    private static M2BoundedSignalOfferV1 bottleReadyForSortOffer;
    private static long eventSequence;

    private static final Queue<String> loaderWorkpieceUpdates =
        new ArrayDeque<String>();
    private static final Queue<String> loaderResourceUpdates =
        new ArrayDeque<String>();
    private static final Queue<String> conveyorWorkpieceUpdates =
        new ArrayDeque<String>();
    private static final Queue<String> conveyorResourceUpdates =
        new ArrayDeque<String>();
    private static final Queue<String> labellerWorkpieceUpdates =
        new ArrayDeque<String>();
    private static final Queue<String> labellerResourceUpdates =
        new ArrayDeque<String>();
    private static final Queue<String> unloaderWorkpieceUpdates =
        new ArrayDeque<String>();
    private static final Queue<String> unloaderResourceUpdates =
        new ArrayDeque<String>();

    static {
        reset();
    }

    private M2MachineStateV1() {
    }

    public static synchronized void reset() {
        loader = new BottleLoaderControllerModelV1();
        conveyor = new ConveyorControllerModelV1(longProperty(
            "m2.conveyor.arrivalTimeoutMillis",
            2000L
        ));
        labeller = new LabellerControllerModelV1();
        unloader = new BottleUnloaderControllerModelV1(longProperty(
            "m2.bottleDoneHoldMillis",
            500L
        ));
        bottleAtConveyorOffer = newHandoffOffer();
        loadBottleOffer = newHandoffOffer();
        markLabelledOffer = newHandoffOffer();
        unloadReadyOffer = newHandoffOffer();
        p6ClearOffer = newHandoffOffer();
        bottleReadyForSortOffer = newHandoffOffer();
        eventSequence = 0;
        loaderWorkpieceUpdates.clear();
        loaderResourceUpdates.clear();
        conveyorWorkpieceUpdates.clear();
        conveyorResourceUpdates.clear();
        labellerWorkpieceUpdates.clear();
        labellerResourceUpdates.clear();
        unloaderWorkpieceUpdates.clear();
        unloaderResourceUpdates.clear();
    }

    public static synchronized int getLoaderStatus() {
        return loader.getStatus();
    }

    public static synchronized boolean startLoaderBatch(int quantity) {
        boolean accepted = loader.startBatch(quantity);
        if (accepted) {
            loaderResourceUpdates.add(resourceUpdate(
                "LOADER-1", "LOADER", "-", M2StatusV1.READY,
                "BATCH_READY", "-"
            ));
        }
        return accepted;
    }

    public static synchronized boolean acceptLoadProfile(String payload) {
        String bottleId = bottleIdFromContext(payload);
        boolean known = bottleId != null &&
            loader.hasAcceptedProfile(bottleId);
        boolean accepted = loader.acceptProfile(payload);
        if (accepted && !known) {
            M2BottleContextV1 context = M2BottleContextV1.parse(payload);
            loaderWorkpieceUpdates.add(workpieceUpdate(
                context.getBottleId(), "CREATED", "LOADER-1",
                context.encodeDetails()
            ));
        }
        return accepted;
    }

    public static synchronized String takeLoadCommand(
        boolean entryAvailable
    ) {
        boolean handoffClear = !bottleAtConveyorOffer.isActive() &&
            !loadBottleOffer.isActive();
        String bottleId = loader.takeLoadCommand(
            entryAvailable && handoffClear
        );
        if (bottleId != null) {
            loaderResourceUpdates.add(resourceUpdate(
                "LOADER-1", "LOADER", bottleId, M2StatusV1.BUSY,
                "PICK_PLACE", "-"
            ));
        }
        return bottleId;
    }

    public static synchronized boolean confirmLoaded(String bottleId) {
        boolean accepted = loader.confirmLoaded(bottleId);
        if (accepted) {
            loaderWorkpieceUpdates.add(workpieceUpdate(
                bottleId, "LOADED", "LOADER-1", "-"
            ));
            loaderResourceUpdates.add(resourceUpdate(
                "LOADER-1", "LOADER", bottleId, M2StatusV1.DONE,
                "LOAD_CONFIRMED", "-"
            ));
        }
        return accepted;
    }

    public static synchronized String nextBottleAtConveyorOffer() {
        return nextBottleAtConveyorOffer(System.currentTimeMillis());
    }

    static synchronized String nextBottleAtConveyorOffer(long nowMillis) {
        if (!bottleAtConveyorOffer.isActive()) {
            String payload = loader.takeLoadedContext();
            if (payload != null) {
                bottleAtConveyorOffer.arm(
                    bottleIdFromContext(payload), payload, nowMillis
                );
            }
        }
        return bottleAtConveyorOffer.nextReactionValue(nowMillis);
    }

    public static synchronized int getConveyorStatus() {
        return conveyor.getStatus();
    }

    public static synchronized boolean isConveyorEntryAvailable() {
        return conveyor.canAcceptBottle() &&
            !bottleAtConveyorOffer.isActive() &&
            !loadBottleOffer.isActive();
    }

    public static synchronized boolean offerConveyorBottle(String payload) {
        String bottleId = bottleIdFromContext(payload);
        boolean known = bottleId != null && conveyor.hasSeenBottle(bottleId);
        if (!known && loadBottleOffer.isActive()) {
            return false;
        }
        boolean accepted = conveyor.offerBottle(payload);
        if (accepted) {
            bottleAtConveyorOffer.acknowledge(bottleId);
            if (!known) {
                conveyorResourceUpdates.add(resourceUpdate(
                    "CONVEYOR-1", "CONVEYOR", bottleId,
                    M2StatusV1.READY, "TRANSFER_READY", "-"
                ));
            }
        }
        return accepted;
    }

    public static synchronized String takeConveyorTransferContext() {
        return conveyor.takeTransferContext();
    }

    public static synchronized boolean startConveyor(long nowMillis) {
        boolean accepted = conveyor.startTransfer(nowMillis);
        if (accepted) {
            conveyorResourceUpdates.add(resourceUpdate(
                "CONVEYOR-1", "CONVEYOR", conveyor.getActiveBottleId(),
                M2StatusV1.BUSY, "MOVE_TO_P1", "-"
            ));
        }
        return accepted;
    }

    public static synchronized boolean isConveyorMotorEnabled() {
        return conveyor.isMotorEnabled();
    }

    public static synchronized boolean acceptP1Feedback(String payload) {
        int before = conveyor.getStatus();
        boolean accepted = conveyor.acceptP1Feedback(payload);
        if (accepted && before != M2StatusV1.DONE &&
            conveyor.getStatus() == M2StatusV1.DONE) {
            String bottleId = payload.split("\\|", -1)[0];
            conveyorWorkpieceUpdates.add(workpieceUpdate(
                bottleId, "P1", "ROTARY-P1", "-"
            ));
            conveyorResourceUpdates.add(resourceUpdate(
                "CONVEYOR-1", "CONVEYOR", bottleId, M2StatusV1.DONE,
                "P1_CONFIRMED", "-"
            ));
        }
        return accepted;
    }

    public static synchronized void tickConveyor(long nowMillis) {
        conveyor.tick(nowMillis, SOURCE_EPOCH);
    }

    public static synchronized String takeConveyorFault() {
        String payload = conveyor.takeFaultPayload();
        if (payload != null) {
            String bottleId = payload.split("\\|", -1)[6];
            conveyorResourceUpdates.add(resourceUpdate(
                "CONVEYOR-1", "CONVEYOR", bottleId, M2StatusV1.FAULT,
                "STOPPED", "ARRIVAL_TIMEOUT"
            ));
        }
        return payload;
    }

    public static synchronized boolean acceptConveyorRecoveryIntent(
        String payload,
        long nowMillis
    ) {
        return conveyor.acceptRecoveryRequest(payload, nowMillis);
    }

    public static synchronized String nextLoadBottleOffer() {
        return nextLoadBottleOffer(System.currentTimeMillis());
    }

    static synchronized String nextLoadBottleOffer(long nowMillis) {
        if (!loadBottleOffer.isActive()) {
            String bottleId = conveyor.takeLoadBottle();
            if (bottleId != null) {
                loadBottleOffer.arm(bottleId, bottleId, nowMillis);
            }
        }
        return loadBottleOffer.nextReactionValue(nowMillis);
    }

    public static synchronized String takeConveyorRecoveryEvidence() {
        return conveyor.takeRecoveryEvidence();
    }

    public static synchronized int getLabellerStatus() {
        return labeller.getStatus();
    }

    public static synchronized boolean offerBottleAtLabel(String bottleId) {
        boolean known = labeller.hasSeenBottle(bottleId);
        boolean accepted = labeller.offerBottle(bottleId);
        if (accepted && !known) {
            labellerWorkpieceUpdates.add(workpieceUpdate(
                bottleId, "P6", "LABELLER-1", "-"
            ));
            labellerResourceUpdates.add(resourceUpdate(
                "LABELLER-1", "LABELLER", bottleId, M2StatusV1.BUSY,
                "APPLY_LABEL", "-"
            ));
        }
        return accepted;
    }

    public static synchronized String takeLabelCommand() {
        return labeller.takeLabelCommand();
    }

    public static synchronized boolean acceptLabelVerification(
        String payload
    ) {
        boolean accepted = labeller.acceptVerification(payload);
        if (accepted) {
            String[] fields = payload.split("\\|", -1);
            if ("PASS".equals(fields[1])) {
                labellerWorkpieceUpdates.add(workpieceUpdate(
                    fields[0], "LABELLED", "LABELLER-1", "-"
                ));
                labellerResourceUpdates.add(resourceUpdate(
                    "LABELLER-1", "LABELLER", fields[0],
                    M2StatusV1.DONE, "LABEL_VERIFIED", "-"
                ));
            }
            else {
                labellerResourceUpdates.add(resourceUpdate(
                    "LABELLER-1", "LABELLER", fields[0],
                    M2StatusV1.FAULT, "STOPPED", "LABEL_VERIFY_FAIL"
                ));
            }
        }
        return accepted;
    }

    public static synchronized String nextMarkLabelledOffer() {
        return nextMarkLabelledOffer(System.currentTimeMillis());
    }

    static synchronized String nextMarkLabelledOffer(long nowMillis) {
        if (!markLabelledOffer.isActive()) {
            String bottleId = labeller.takeMarkLabelled();
            if (bottleId != null) {
                markLabelledOffer.arm(bottleId, bottleId, nowMillis);
            }
        }
        return markLabelledOffer.nextReactionValue(nowMillis);
    }

    public static synchronized String nextUnloadReadyOffer() {
        return nextUnloadReadyOffer(System.currentTimeMillis());
    }

    static synchronized String nextUnloadReadyOffer(long nowMillis) {
        if (!unloadReadyOffer.isActive()) {
            String bottleId = labeller.takeUnloadReady();
            if (bottleId != null) {
                unloadReadyOffer.arm(bottleId, bottleId, nowMillis);
            }
        }
        return unloadReadyOffer.nextReactionValue(nowMillis);
    }

    public static synchronized boolean resetLabellerFault(
        boolean labelPathClear,
        boolean verifierHealthy
    ) {
        boolean reset = labeller.resetFault(
            labelPathClear,
            verifierHealthy
        );
        if (reset) {
            labellerResourceUpdates.add(resourceUpdate(
                "LABELLER-1", "LABELLER", "-", M2StatusV1.READY,
                "RESET_AFTER_EVIDENCE", "-"
            ));
        }
        return reset;
    }

    public static synchronized int getUnloaderStatus() {
        return unloader.getStatus();
    }

    public static synchronized boolean acceptUnloadProfile(String payload) {
        return unloader.acceptProfile(payload);
    }

    public static synchronized boolean acceptUnloadReady(String bottleId) {
        boolean accepted = unloader.acceptUnloadReady(bottleId);
        if (accepted) {
            unloadReadyOffer.acknowledge(bottleId);
        }
        return accepted;
    }

    public static synchronized String takeUnloadCommand() {
        if (p6ClearOffer.isActive() ||
            bottleReadyForSortOffer.isActive()) {
            return null;
        }
        String bottleId = unloader.takeUnloadCommand();
        if (bottleId != null) {
            unloaderResourceUpdates.add(resourceUpdate(
                "UNLOADER-1", "UNLOADER", bottleId, M2StatusV1.BUSY,
                "REMOVE_FROM_P6", "-"
            ));
        }
        return bottleId;
    }

    public static synchronized boolean acceptRemovalConfirmed(
        String payload,
        long nowMillis
    ) {
        boolean accepted = unloader.acceptRemovalConfirmed(
            payload,
            nowMillis
        );
        if (accepted) {
            String bottleId = payload.split("\\|", -1)[0];
            unloaderWorkpieceUpdates.add(workpieceUpdate(
                bottleId, "UNLOADED", "UNLOADER-1", "-"
            ));
            unloaderWorkpieceUpdates.add(workpieceUpdate(
                bottleId, "COMPLETE", "COLLECTION", "-"
            ));
            unloaderResourceUpdates.add(resourceUpdate(
                "UNLOADER-1", "UNLOADER", bottleId, M2StatusV1.DONE,
                "REMOVAL_CONFIRMED", "-"
            ));
        }
        return accepted;
    }

    public static synchronized String nextP6ClearOffer() {
        return nextP6ClearOffer(System.currentTimeMillis());
    }

    static synchronized String nextP6ClearOffer(long nowMillis) {
        if (!p6ClearOffer.isActive()) {
            String bottleId = unloader.takeP6Clear();
            if (bottleId != null) {
                p6ClearOffer.arm(bottleId, bottleId, nowMillis);
            }
        }
        return p6ClearOffer.nextReactionValue(nowMillis);
    }

    public static synchronized String nextBottleReadyForSortOffer() {
        return nextBottleReadyForSortOffer(System.currentTimeMillis());
    }

    static synchronized String nextBottleReadyForSortOffer(long nowMillis) {
        if (!bottleReadyForSortOffer.isActive()) {
            String payload = unloader.takeSortContext();
            if (payload != null) {
                bottleReadyForSortOffer.arm(
                    bottleIdFromContext(payload), payload, nowMillis
                );
            }
        }
        return bottleReadyForSortOffer.nextReactionValue(nowMillis);
    }

    public static synchronized boolean isBottleDonePresent(long nowMillis) {
        int before = unloader.getStatus();
        boolean present = unloader.isBottleDonePresent(nowMillis);
        if (before == M2StatusV1.DONE &&
            unloader.getStatus() == M2StatusV1.READY) {
            unloaderResourceUpdates.add(resourceUpdate(
                "UNLOADER-1", "UNLOADER", "-", M2StatusV1.READY,
                "AWAIT_BOTTLE", "-"
            ));
        }
        return present;
    }

    public static synchronized String takeLoaderWorkpieceUpdate() {
        return loaderWorkpieceUpdates.poll();
    }

    public static synchronized String takeLoaderResourceUpdate() {
        return loaderResourceUpdates.poll();
    }

    public static synchronized String takeConveyorWorkpieceUpdate() {
        return conveyorWorkpieceUpdates.poll();
    }

    public static synchronized String takeConveyorResourceUpdate() {
        return conveyorResourceUpdates.poll();
    }

    public static synchronized String takeLabellerWorkpieceUpdate() {
        return labellerWorkpieceUpdates.poll();
    }

    public static synchronized String takeLabellerResourceUpdate() {
        return labellerResourceUpdates.poll();
    }

    public static synchronized String takeUnloaderWorkpieceUpdate() {
        return unloaderWorkpieceUpdates.poll();
    }

    public static synchronized String takeUnloaderResourceUpdate() {
        return unloaderResourceUpdates.poll();
    }

    private static String workpieceUpdate(
        String bottleId,
        String eventType,
        String resourceId,
        String details
    ) {
        long now = System.currentTimeMillis();
        String eventId = "M2-W-" + (++eventSequence) + "-" + eventType;
        return M2TwinUpdateV1.workpiece(
            eventId, bottleId, eventType, resourceId, details, now
        );
    }

    private static String resourceUpdate(
        String resourceId,
        String resourceType,
        String bottleId,
        int status,
        String operation,
        String fault
    ) {
        long now = System.currentTimeMillis();
        String eventId = "M2-R-" + (++eventSequence) + "-" + resourceId;
        return M2TwinUpdateV1.resource(
            eventId, resourceId, resourceType, bottleId, status,
            operation, fault, now
        );
    }

    private static long longProperty(String name, long fallback) {
        String value = System.getProperty(name);
        if (value == null || !value.matches("[1-9][0-9]*")) {
            return fallback;
        }
        return Long.parseLong(value);
    }

    private static int intProperty(String name, int fallback) {
        String value = System.getProperty(name);
        if (value == null || !value.matches("[1-9][0-9]*")) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static M2BoundedSignalOfferV1 newHandoffOffer() {
        return new M2BoundedSignalOfferV1(
            intProperty("m2.handoff.maximumOffers", 3),
            longProperty("m2.handoff.retryIntervalMillis", 600L)
        );
    }

    private static String bottleIdFromContext(String payload) {
        if (payload == null) {
            return null;
        }
        int separator = payload.indexOf('|');
        return separator < 0 ? payload : payload.substring(0, separator);
    }
}
