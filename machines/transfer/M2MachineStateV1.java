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
    private static M2HeldSignalOfferV1 bottleAtConveyorOffer;
    private static M2HeldSignalOfferV1 loadBottleOffer;
    private static M2HeldSignalOfferV1 markLabelledOffer;
    private static M2HeldSignalOfferV1 unloadReadyOffer;
    private static M2HeldSignalOfferV1 p6ClearOffer;
    private static M2HeldSignalOfferV1 bottleReadyForSortOffer;
    private static M2HeldSignalOfferV1 unloadCommandOffer;
    private static M2HeldSignalOfferV1 loadCommandOffer;
    private static M2HeldSignalOfferV1 labelCommandOffer;
    private static M2HeldSignalOfferV1 conveyorTransferOffer;
    private static long eventSequence;
    private static long sourceGeneration;
    private static boolean requireFreshProfile;
    private static boolean startOrderArmed = true;
    private static int pendingBatchQuantity;

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
        unloadCommandOffer = newActuationOffer();
        loadCommandOffer = newActuationOffer();
        labelCommandOffer = newActuationOffer();
        conveyorTransferOffer = newActuationOffer();
        requireFreshProfile = false;
        startOrderArmed = true;
        pendingBatchQuantity = 0;
        loaderWorkpieceUpdates.clear();
        loaderResourceUpdates.clear();
        conveyorWorkpieceUpdates.clear();
        conveyorResourceUpdates.clear();
        labellerWorkpieceUpdates.clear();
        labellerResourceUpdates.clear();
        unloaderWorkpieceUpdates.clear();
        unloaderResourceUpdates.clear();
    }

    /** Production reset retains globally unique event IDs and advances FT epoch. */
    public static synchronized void resetForSystemReset() {
        reset();
        sourceGeneration++;
        requireFreshProfile = true;
        startOrderArmed = false;
    }

    public static synchronized void observeStartOrderAbsent() {
        if (!M2SystemResetStateV1.isQuarantined()) { startOrderArmed = true; }
    }

    public static synchronized String getSourceEpoch() {
        return sourceGeneration == 0 ? SOURCE_EPOCH : SOURCE_EPOCH + "R" + sourceGeneration;
    }

    public static synchronized long getEventSequence() { return eventSequence; }

    public static synchronized boolean isStartOrderArmed() { return startOrderArmed; }

    public static synchronized int getLoaderStatus() {
        return loader.getStatus();
    }

    public static synchronized boolean startLoaderBatch(int quantity) {
        if (M2SystemResetStateV1.isQuarantined() || !startOrderArmed || quantity <= 0) {
            return false;
        }
        if (requireFreshProfile) {
            if (pendingBatchQuantity != 0 && pendingBatchQuantity != quantity) { return false; }
            pendingBatchQuantity = quantity;
            startOrderArmed = false;
            return true;
        }
        return activateLoaderBatch(quantity);
    }

    private static boolean activateLoaderBatch(int quantity) {
        boolean accepted = loader.startBatch(quantity);
        if (accepted) {
            startOrderArmed = false;
            loaderResourceUpdates.add(resourceUpdate(
                "LOADER-1", "LOADER", "-", M2StatusV1.READY,
                "BATCH_READY", "-"
            ));
        }
        return accepted;
    }

    public static synchronized boolean acceptLoadProfile(String payload) {
        String bottleId = bottleIdFromContext(payload);
        if (!M2SystemResetStateV1.allowBottle(bottleId)) { return false; }
        boolean known = bottleId != null &&
            loader.hasAcceptedProfile(bottleId);
        boolean accepted = loader.acceptProfile(payload);
        if (accepted && !known) {
            M2SystemResetStateV1.observeBottle(bottleId);
            M2BottleContextV1 context = M2BottleContextV1.parse(payload);
            loaderWorkpieceUpdates.add(workpieceUpdate(
                context.getBottleId(), "CREATED", "LOADER-1",
                context.encodeDetails()
            ));
            if (requireFreshProfile) {
                requireFreshProfile = false;
                if (pendingBatchQuantity > 0) {
                    int quantity = pendingBatchQuantity;
                    pendingBatchQuantity = 0;
                    activateLoaderBatch(quantity);
                }
            }
        }
        return accepted;
    }

    public static synchronized String takeLoadCommand(
        boolean entryAvailable
    ) {
        if (M2SystemResetStateV1.isQuarantined() || requireFreshProfile) { return null; }
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
        if (!M2SystemResetStateV1.allowBottle(bottleId)) { return false; }
        boolean accepted = loader.confirmLoaded(bottleId);
        if (accepted) {
            loadCommandOffer.acknowledge(bottleId);
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

    public static synchronized String nextLoadCommandOffer(boolean entryAvailable) {
        return nextLoadCommandOffer(entryAvailable, System.currentTimeMillis());
    }

    static synchronized String nextLoadCommandOffer(boolean entryAvailable, long nowMillis) {
        if (M2SystemResetStateV1.isQuarantined()) { return null; }
        if (!loadCommandOffer.isActive()) {
            String bottleId = takeLoadCommand(entryAvailable);
            if (bottleId != null) {
                loadCommandOffer.arm(bottleId, bottleId, nowMillis);
            }
        }
        return loadCommandOffer.nextReactionValue(nowMillis);
    }

    public static synchronized boolean acknowledgeLoadCommand(String bottleId) {
        return loadCommandOffer.acknowledge(bottleId);
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
                loaderResourceUpdates.add(resourceUpdate(
                    "LOADER-1", "LOADER", "-", loader.getStatus(),
                    loader.isBatchComplete() ? "BATCH_COMPLETE" : "AWAIT_BOTTLE", "-"
                ));
            }
        }
        return bottleAtConveyorOffer.nextReactionValue(nowMillis);
    }

    public static synchronized int getConveyorStatus() {
        return conveyor.getStatus();
    }

    public static synchronized boolean isConveyorEntryAvailable() {
        return !M2SystemResetStateV1.isQuarantined() && conveyor.canAcceptBottle() &&
            !bottleAtConveyorOffer.isActive() &&
            !loadBottleOffer.isActive();
    }

    public static synchronized boolean offerConveyorBottle(String payload) {
        String bottleId = bottleIdFromContext(payload);
        if (!M2SystemResetStateV1.allowBottle(bottleId)) { return false; }
        boolean known = bottleId != null && conveyor.hasSeenBottle(bottleId);
        if (!known && loadBottleOffer.isActive()) {
            return false;
        }
        boolean accepted = conveyor.offerBottle(payload);
        if (accepted) {
            M2SystemResetStateV1.observeBottle(bottleId);
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
        if (M2SystemResetStateV1.isQuarantined()) { return null; }
        return conveyor.takeTransferContext();
    }

    public static synchronized String nextConveyorTransferOffer() {
        return nextConveyorTransferOffer(System.currentTimeMillis());
    }

    static synchronized String nextConveyorTransferOffer(long nowMillis) {
        if (M2SystemResetStateV1.isQuarantined()) { return null; }
        if (!conveyorTransferOffer.isActive()) {
            String bottleId = takeConveyorTransferContext();
            if (bottleId != null) {
                conveyorTransferOffer.arm(bottleId, bottleId, nowMillis);
                startConveyor(nowMillis);
            }
        }
        return conveyorTransferOffer.nextReactionValue(nowMillis);
    }

    public static synchronized boolean acknowledgeConveyorTransfer(String bottleId) {
        return conveyorTransferOffer.acknowledge(bottleId);
    }

    public static synchronized boolean startConveyor(long nowMillis) {
        if (M2SystemResetStateV1.isQuarantined()) { return false; }
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
        return !M2SystemResetStateV1.isQuarantined() && conveyor.isMotorEnabled();
    }

    public static synchronized boolean acceptP1Feedback(String payload) {
        if (!M2SystemResetStateV1.allowBottle(bottleIdFromContext(payload))) { return false; }
        int before = conveyor.getStatus();
        boolean accepted = conveyor.acceptP1Feedback(payload);
        if (accepted) { conveyorTransferOffer.acknowledge(bottleIdFromContext(payload)); }
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
        if (!M2SystemResetStateV1.isQuarantined()) { conveyor.tick(nowMillis, getSourceEpoch()); }
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
        String[] fields = payload == null ? new String[0] : payload.split("\\|", -1);
        if (M2SystemResetStateV1.isQuarantined() || fields.length != 6 ||
            !getSourceEpoch().equals(fields[2])) { return false; }
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
                conveyorResourceUpdates.add(resourceUpdate(
                    "CONVEYOR-1", "CONVEYOR", "-", conveyor.getStatus(),
                    "AWAIT_BOTTLE", "-"
                ));
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
        if (!M2SystemResetStateV1.allowBottle(bottleId)) { return false; }
        boolean known = labeller.hasSeenBottle(bottleId);
        boolean accepted = labeller.offerBottle(bottleId);
        if (accepted && !known) {
            M2SystemResetStateV1.observeBottle(bottleId);
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
        if (M2SystemResetStateV1.isQuarantined()) { return null; }
        return labeller.takeLabelCommand();
    }

    public static synchronized String nextLabelCommandOffer() {
        return nextLabelCommandOffer(System.currentTimeMillis());
    }

    static synchronized String nextLabelCommandOffer(long nowMillis) {
        if (M2SystemResetStateV1.isQuarantined()) { return null; }
        if (!labelCommandOffer.isActive()) {
            String command = takeLabelCommand();
            if (command != null) {
                labelCommandOffer.arm(bottleIdFromContext(command), command, nowMillis);
            }
        }
        return labelCommandOffer.nextReactionValue(nowMillis);
    }

    public static synchronized boolean acknowledgeLabelCommand(String bottleId) {
        return labelCommandOffer.acknowledge(bottleId);
    }

    public static synchronized boolean acceptLabelVerification(
        String payload
    ) {
        if (!M2SystemResetStateV1.allowBottle(bottleIdFromContext(payload))) { return false; }
        boolean accepted = labeller.acceptVerification(payload);
        if (accepted) {
            String[] fields = payload.split("\\|", -1);
            labelCommandOffer.acknowledge(fields[0]);
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
                publishLabellerReadyIfDrained();
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
                publishLabellerReadyIfDrained();
            }
        }
        return unloadReadyOffer.nextReactionValue(nowMillis);
    }

    /** Mirror the actual rearm regardless of which handoff drains last. */
    private static void publishLabellerReadyIfDrained() {
        if (labeller.getStatus() == M2StatusV1.READY) {
            labellerResourceUpdates.add(resourceUpdate(
                "LABELLER-1", "LABELLER", "-", M2StatusV1.READY,
                "AWAIT_BOTTLE", "-"
            ));
        }
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
        String bottleId = bottleIdFromContext(payload);
        if (!M2SystemResetStateV1.allowBottle(bottleId)) { return false; }
        boolean accepted = unloader.acceptProfile(payload);
        if (accepted) { M2SystemResetStateV1.observeBottle(bottleId); }
        return accepted;
    }

    public static synchronized boolean acceptUnloadReady(String bottleId) {
        if (!M2SystemResetStateV1.allowBottle(bottleId)) { return false; }
        boolean accepted = unloader.acceptUnloadReady(bottleId);
        if (accepted) {
            M2SystemResetStateV1.observeBottle(bottleId);
            unloadReadyOffer.acknowledge(bottleId);
        }
        return accepted;
    }

    public static synchronized String takeUnloadCommand() {
        if (M2SystemResetStateV1.isQuarantined()) { return null; }
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

    public static synchronized String nextUnloadCommandOffer() {
        return nextUnloadCommandOffer(System.currentTimeMillis());
    }

    static synchronized String nextUnloadCommandOffer(long nowMillis) {
        if (M2SystemResetStateV1.isQuarantined()) { return null; }
        if (!unloadCommandOffer.isActive()) {
            String bottleId = takeUnloadCommand();
            if (bottleId != null) {
                unloadCommandOffer.arm(bottleId, bottleId, nowMillis);
            }
        }
        return unloadCommandOffer.nextReactionValue(nowMillis);
    }

    /** Optional local acknowledgement; remote deployments retain bounded retries. */
    public static synchronized boolean acknowledgeUnloadCommand(String bottleId) {
        return unloadCommandOffer.acknowledge(bottleId);
    }

    public static synchronized boolean acceptRemovalConfirmed(
        String payload,
        long nowMillis
    ) {
        if (!M2SystemResetStateV1.allowBottle(bottleIdFromContext(payload))) { return false; }
        boolean accepted = unloader.acceptRemovalConfirmed(
            payload,
            nowMillis
        );
        if (accepted) {
            String bottleId = payload.split("\\|", -1)[0];
            unloadCommandOffer.acknowledge(bottleId);
            unloaderWorkpieceUpdates.add(workpieceUpdate(
                bottleId, "UNLOADED", "UNLOADER-1", "-"
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
        if (M2SystemResetStateV1.isQuarantined()) { return false; }
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
        String payload = M2TwinUpdateV1.workpiece(
            eventId, bottleId, eventType, resourceId, details, now
        );
        DigitalTwinStateV1.enqueueLocalWorkpiece(payload);
        return payload;
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
        String payload = M2TwinUpdateV1.resource(
            eventId, resourceId, resourceType, bottleId, status,
            operation, fault, now
        );
        DigitalTwinStateV1.enqueueLocalResource(payload);
        return payload;
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

    private static M2HeldSignalOfferV1 newHandoffOffer() {
        return new M2HeldSignalOfferV1(
            intProperty("m2.handoff.maximumOffers", 5),
            longProperty("m2.handoff.holdMillis", 200L),
            longProperty("m2.handoff.retryGapMillis", 100L)
        );
    }

    private static M2HeldSignalOfferV1 newActuationOffer() {
        return new M2HeldSignalOfferV1(10, 100L, 25L);
    }

    private static String bottleIdFromContext(String payload) {
        if (payload == null) {
            return null;
        }
        int separator = payload.indexOf('|');
        return separator < 0 ? payload : payload.substring(0, separator);
    }
}
