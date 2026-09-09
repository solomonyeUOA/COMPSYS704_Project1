/**
 * Shared order/status state for the single CoordinatorCD instance.
 *
 * The course SystemJ compiler does not safely lift ordinary local variables
 * referenced by separate parallel branches. Keeping the data here lets the
 * .sysj source retain independent, non-blocking listeners without putting any
 * machine-level behaviour in the Coordinator.
 */
public final class CoordinatorStateV1 {
    private static final int COMPLETION_TRANSMISSION_ATTEMPTS = 3;
    private static final long COMPLETION_RETRY_MILLIS = 500L;
    private static final int SIMULATION_BATCH_TRANSMISSION_ATTEMPTS = 3;
    private static final long SIMULATION_BATCH_RETRY_MILLIS = 600L;
    private static final int RESET_TRANSMISSION_ATTEMPTS = 3;
    private static final long RESET_SIGNAL_HOLD_MILLIS = 500L;
    private static final long RESET_RETRY_MILLIS = 250L;
    private static final long COMPLETION_SIGNAL_HOLD_MILLIS = Math.max(
        1L,
        Long.getLong(
            "abs.coordinator.completionSignalHoldMillis",
            Long.valueOf(500L)
        ).longValue()
    );

    private CoordinatorStateV1() {
    }

    public static int loaderStatus = 0;
    public static int conveyorStatus = 0;
    public static int rotaryStatus = 0;
    public static int fillerAStatus = 0;
    public static int fillerBStatus = 0;
    public static int lidStatus = 0;
    public static int capperStatus = 0;
    public static int unloaderStatus = 0;
    public static int labellerStatus = 0;
    private static java.math.BigInteger resetWatermark = java.math.BigInteger.valueOf(-1L);
    private static final java.util.Set<String> acceptedOrderIds = new java.util.HashSet<String>();
    private static long nextTwinContextMillis;
    private static long startOrderUntilMillis;
    private static int m2ResetEpoch;
    private static final java.util.Set<String> observedFtKeys = new java.util.HashSet<String>();
    private static final java.util.Set<String> retiredFtKeys = new java.util.HashSet<String>();

    /** Non-null only when the active payload used the frozen V1 format. */
    public static OrderV1 activeOrder = null;
    /** Non-null only when the active payload used the size-aware V2 format. */
    public static OrderV2 activeOrderV2 = null;
    public static int currentProductIndex = 0;
    public static String currentSizeCode = OrderV2.SMALL;
    public static int currentCapacityMl = OrderV2.SMALL_CAPACITY_ML;
    public static int currentLiquidARatio = 0;
    public static int currentLiquidBRatio = 0;
    public static int requiredBottles = 0;
    public static int completedBottles = 0;
    public static boolean orderActive = false;
    public static boolean bottleDoneSignalLatched = false;
    public static boolean bottleDoneRearmRequired = false;
    public static long orderStartMillis = 0;
    public static long nextStatusPollMillis =
        System.currentTimeMillis() + 1000;
    public static String pendingCompletionPayload = "";
    public static boolean completionPending = false;
    public static long completionSendAfterMillis = 0;
    public static int completionTransmissionsRemaining = 0;
    public static int lastCompletionAttempt = 0;
    public static boolean completionSignalActive = false;
    public static long completionSignalUntilMillis = 0;
    public static boolean completionTransmissionStarted = false;
    public static String lastAcceptedOrderId = "";

    // Simulation/integration-only M1 -> M4 batch trigger. This state does not
    // alter START_ORDER or any frozen Controller interface.
    private static final M1SimulationBatchOfferV1 m4SimulationBatchOffer =
        new M1SimulationBatchOfferV1(
            SIMULATION_BATCH_TRANSMISSION_ATTEMPTS,
            SIMULATION_BATCH_RETRY_MILLIS
        );
    public static boolean m4SimulationBatchTransmissionStarted = false;
    public static int lastM4SimulationBatchAttempt = 0;

    // Whole-system reset orchestration. M1 clears its own state immediately,
    // then remains pending until M2, M3 and M4 independently acknowledge the
    // same reset identity. These helpers never infer a teammate ACK.
    private static final BoundedStringSignalOfferV1 m2ResetOffer =
        newResetOffer();
    private static final BoundedStringSignalOfferV1 m3ResetOffer =
        newResetOffer();
    private static final BoundedStringSignalOfferV1 m4ResetOffer =
        newResetOffer();
    private static final BoundedStringSignalOfferV1 visualisationResetOffer =
        newResetOffer();
    private static final BoundedStringSignalOfferV1 resetCompletionOffer =
        newResetOffer();
    public static String activeSystemResetId = "";
    public static String lastSystemResetId = "";
    private static final java.util.Set<String> processedSystemResetIds =
        new java.util.HashSet<String>();
    public static boolean systemResetPendingExternalAck = false;
    public static boolean systemResetCompletionPending = false;
    public static boolean m2SystemResetAcknowledged = false;
    public static boolean m3SystemResetAcknowledged = false;
    public static boolean m4SystemResetAcknowledged = false;
    public static int lastSystemResetCompletionAttempt = 0;
    public static boolean systemResetCompletionTransmissionStarted = false;

    // M3-facing V2.1 safety-coordination state. These fields deliberately
    // store opaque String payloads because the frozen V2.1 contract defines
    // the signal semantics but not an exact M1 payload field order.
    public static String latestFtFaultAlert = "";
    public static String pendingFtSafeStopRequest = "";
    public static String latestFtRecoveryReady = "";
    public static String latestFtRecoveryFailed = "";
    public static boolean ftCoordinationHold = false;
    public static boolean ftSafeStopEstablished = false;
    public static boolean ftBatchTransitionHeld = false;

    // M1-only, display-only projection of the FT evidence already received by
    // CoordinatorCD. It never creates an acknowledgement, recovery decision
    // or Controller command.
    private static String ftVisualState = "NORMAL";
    private static String ftVisualSource = "M3_FAULT_SUPERVISOR";
    private static String ftVisualEvent = "none";
    private static String ftVisualSafeStop = "NOT_REQUESTED";
    private static String ftVisualRecovery = "NOT_ACTIVE";
    private static String pendingFtVisualEvidence = visualFtEvidence();

    /** Parses and accepts a new order only when no order is active. */
    public static boolean accept(String payload) {
        // completionPending belongs to the previous order's transport retry.
        // POS may submit the next order after receiving retry copy 1 while
        // copies 2/3 are still pending, so it must not gate production reuse.
        if (orderActive || ftCoordinationHold ||
            systemResetPendingExternalAck) {
            return false;
        }

        OrderV2 parsedOrderV2 = OrderV2.parse(payload);
        OrderV1 parsedOrderV1 = parsedOrderV2 == null ?
            OrderV1.parse(payload) : null;
        if (parsedOrderV2 == null && parsedOrderV1 == null) {
            return false;
        }
        String parsedOrderId = parsedOrderV2 != null ?
            parsedOrderV2.orderId : parsedOrderV1.orderId;
        if (acceptedOrderIds.contains(parsedOrderId)) {
            return false;
        }

        activeOrderV2 = parsedOrderV2;
        activeOrder = parsedOrderV1;
        lastAcceptedOrderId = parsedOrderId;
        acceptedOrderIds.add(parsedOrderId);
        currentProductIndex = 0;
        loadCurrentProduct();
        orderActive = true;
        orderStartMillis = System.currentTimeMillis();
        return true;
    }

    /** Returns true when the current product has received all BOTTLE_DONEs. */
    public static boolean recordBottleDone() {
        if (!orderActive || systemResetPendingExternalAck) {
            return false;
        }

        completedBottles++;
        return completedBottles == requiredBottles;
    }

    /**
     * Converts a bounded PRESENT window for the pure BOTTLE_DONE signal into
     * one logical rising-edge event. The latch is deliberately not reset when
     * an order completes, so a late held signal cannot count toward the next
     * order before an ABSENT reaction has been observed.
     */
    public static boolean consumeBottleDoneEdge(boolean signalPresent) {
        if (bottleDoneRearmRequired) {
            if (!signalPresent) {
                bottleDoneRearmRequired = false;
                bottleDoneSignalLatched = false;
            }
            return false;
        }
        if (!signalPresent) {
            bottleDoneSignalLatched = false;
            return false;
        }
        if (bottleDoneSignalLatched) {
            return false;
        }
        bottleDoneSignalLatched = true;
        return true;
    }

    public static boolean hasNextProduct() {
        return activeProductCount() > 0 &&
            currentProductIndex + 1 < activeProductCount();
    }

    public static void advanceToNextProduct() {
        currentProductIndex++;
        loadCurrentProduct();
    }

    public static String currentProductId() {
        return activeOrderV2 != null ?
            activeOrderV2.productIds[currentProductIndex] :
            activeOrder.productIds[currentProductIndex];
    }

    public static String currentOrderId() {
        if (activeOrderV2 != null) {
            return activeOrderV2.orderId;
        }
        return activeOrder == null ? "" : activeOrder.orderId;
    }

    public static String currentSizeCode() {
        return currentSizeCode;
    }

    public static int currentCapacityMl() {
        return currentCapacityMl;
    }

    /** Current stable simulation-only batch identity. */
    public static String currentM4SimulationBatchId() {
        return m4SimulationBatchOffer.getBatchId();
    }

    /** Stable batchId|quantity|sizeCode payload, including after retries drain. */
    public static String currentM4SimulationBatchPayload() {
        return m4SimulationBatchOffer.getStablePayload();
    }

    /**
     * Returns at most three identical copies with a 600 ms retry interval and
     * an ABSENT reaction between copies.
     */
    public static String nextM4SimulationBatchRequest() {
        return nextM4SimulationBatchRequest(System.currentTimeMillis());
    }

    static String nextM4SimulationBatchRequest(long nowMillis) {
        int before = m4SimulationBatchOffer.getOfferCount();
        String payload = m4SimulationBatchOffer.nextReactionValue(nowMillis);
        lastM4SimulationBatchAttempt =
            m4SimulationBatchOffer.getOfferCount();
        m4SimulationBatchTransmissionStarted = payload != null &&
            lastM4SimulationBatchAttempt > before;
        return payload;
    }

    /** Builds the frozen completion payload and releases the order slot. */
    public static void completeOrder() {
        int completionTimeSeconds = (int)(
            (System.currentTimeMillis() - orderStartMillis) / 1000
        );
        pendingCompletionPayload = currentOrderId() + "|COMPLETED|" +
            completionTimeSeconds;
        orderActive = false;
        completionPending = true;
        completionTransmissionsRemaining =
            COMPLETION_TRANSMISSION_ATTEMPTS;
        lastCompletionAttempt = 0;
        completionSignalActive = false;
        completionSignalUntilMillis = 0;
        completionTransmissionStarted = false;
        completionSendAfterMillis = System.currentTimeMillis() + 250;
        nextStatusPollMillis = System.currentTimeMillis() + 1000;
    }

    /**
     * Returns the stored completion payload while its current transport copy
     * must remain PRESENT. Returns null during the inter-copy ABSENT gap.
     */
    public static String nextCompletionTransmission() {
        completionTransmissionStarted = false;
        if (!completionPending) {
            return null;
        }

        long now = System.currentTimeMillis();
        if (completionSignalActive) {
            if (now < completionSignalUntilMillis) {
                return pendingCompletionPayload;
            }
            completionSignalActive = false;
            completionSignalUntilMillis = 0;
            if (completionTransmissionsRemaining > 0) {
                completionSendAfterMillis = now + COMPLETION_RETRY_MILLIS;
            }
            else {
                completionPending = false;
                completionSendAfterMillis = 0;
                pendingCompletionPayload = "";
            }
            return null;
        }

        if (now < completionSendAfterMillis) {
            return null;
        }

        lastCompletionAttempt =
            COMPLETION_TRANSMISSION_ATTEMPTS + 1 -
            completionTransmissionsRemaining;
        completionTransmissionsRemaining--;
        completionSignalActive = true;
        completionSignalUntilMillis =
            now + COMPLETION_SIGNAL_HOLD_MILLIS;
        completionTransmissionStarted = true;
        return pendingCompletionPayload;
    }

    /** Rejects late or held transport copies without restarting an order. */
    public static boolean isDuplicateOfLastAcceptedOrder(String payload) {
        String orderId = parsedOrderId(payload);
        return orderId != null && orderId.equals(lastAcceptedOrderId);
    }

    public static String lifecycleSnapshot() {
        String orderId = currentOrderId().length() == 0 ?
            "none" : currentOrderId();
        return "order=" + orderId +
            " orderActive=" + orderActive +
            " completionPending=" + completionPending +
            " required=" + requiredBottles +
            " completed=" + completedBottles +
            " productIndex=" + currentProductIndex +
            " completionRemaining=" + completionTransmissionsRemaining +
            " completionSignalActive=" + completionSignalActive +
            " m4SimBatch=" + currentM4SimulationBatchPayload() +
            " m4SimAttempt=" + lastM4SimulationBatchAttempt +
            " size=" + currentSizeCode +
            " capacityMl=" + currentCapacityMl +
            " resetState=" + systemResetState() +
            " ftHold=" + ftCoordinationHold +
            " ftSafeStopEstablished=" + ftSafeStopEstablished;
    }

    /** Starts one idempotent whole-system reset orchestration. */
    public static synchronized boolean beginSystemReset(String resetId) {
        return beginSystemReset(resetId, System.currentTimeMillis());
    }

    static synchronized boolean beginSystemReset(
        String resetId,
        long nowMillis
    ) {
        if (!isValidResetId(resetId) ||
            new java.math.BigInteger(resetId.substring(3)).compareTo(resetWatermark) <= 0 ||
            processedSystemResetIds.contains(resetId) ||
            systemResetPendingExternalAck ||
            systemResetCompletionPending) {
            return false;
        }

        activeSystemResetId = resetId;
        resetWatermark = new java.math.BigInteger(resetId.substring(3));
        lastSystemResetId = resetId;
        retiredFtKeys.addAll(observedFtKeys);
        observedFtKeys.clear();
        m2ResetEpoch++;
        processedSystemResetIds.add(resetId);
        clearM1OwnedRuntimeState(nowMillis);
        m2SystemResetAcknowledged = false;
        m3SystemResetAcknowledged = false;
        m4SystemResetAcknowledged = false;
        systemResetPendingExternalAck = true;
        systemResetCompletionPending = false;
        lastSystemResetCompletionAttempt = 0;
        systemResetCompletionTransmissionStarted = false;
        resetCompletionOffer.discard();
        m2ResetOffer.begin(resetId, nowMillis);
        m3ResetOffer.begin(resetId, nowMillis);
        m4ResetOffer.begin(resetId, nowMillis);
        visualisationResetOffer.begin(resetId, nowMillis);
        return true;
    }

    public static synchronized String nextM2SystemReset() {
        return nextM2SystemReset(System.currentTimeMillis());
    }

    static synchronized String nextM2SystemReset(long nowMillis) {
        return m2ResetOffer.nextValue(nowMillis);
    }

    public static synchronized String nextM3SystemReset() {
        return nextM3SystemReset(System.currentTimeMillis());
    }

    static synchronized String nextM3SystemReset(long nowMillis) {
        return m3ResetOffer.nextValue(nowMillis);
    }

    public static synchronized String nextM4SystemReset() {
        return nextM4SystemReset(System.currentTimeMillis());
    }

    static synchronized String nextM4SystemReset(long nowMillis) {
        return m4ResetOffer.nextValue(nowMillis);
    }

    public static synchronized String nextVisualisationSystemReset() {
        return nextVisualisationSystemReset(System.currentTimeMillis());
    }

    static synchronized String nextVisualisationSystemReset(long nowMillis) {
        return visualisationResetOffer.nextValue(nowMillis);
    }

    public static synchronized boolean recordM2SystemResetAck(String resetId) {
        return recordSystemResetAck(2, resetId, System.currentTimeMillis());
    }

    public static synchronized boolean recordM3SystemResetAck(String resetId) {
        return recordSystemResetAck(3, resetId, System.currentTimeMillis());
    }

    public static synchronized boolean recordM4SystemResetAck(String resetId) {
        return recordSystemResetAck(4, resetId, System.currentTimeMillis());
    }

    static synchronized boolean recordSystemResetAck(
        int member,
        String resetId,
        long nowMillis
    ) {
        if (!systemResetPendingExternalAck ||
            !activeSystemResetId.equals(resetId)) {
            return false;
        }

        boolean newlyAcknowledged;
        if (member == 2) {
            newlyAcknowledged = !m2SystemResetAcknowledged;
            m2SystemResetAcknowledged = true;
        }
        else if (member == 3) {
            newlyAcknowledged = !m3SystemResetAcknowledged;
            m3SystemResetAcknowledged = true;
        }
        else if (member == 4) {
            newlyAcknowledged = !m4SystemResetAcknowledged;
            m4SystemResetAcknowledged = true;
        }
        else {
            return false;
        }

        if (m2SystemResetAcknowledged && m3SystemResetAcknowledged &&
            m4SystemResetAcknowledged) {
            systemResetPendingExternalAck = false;
            systemResetCompletionPending = true;
            resetCompletionOffer.begin(
                activeSystemResetId + "|RESET_COMPLETE",
                nowMillis
            );
            ftVisualState = "NORMAL";
            ftVisualSource = "SYSTEM_RESET";
            ftVisualEvent = activeSystemResetId;
            ftVisualSafeStop = "MEMBERS_ACKNOWLEDGED";
            ftVisualRecovery = "RESET_COMPLETE";
            queueFtVisualEvidence();
            System.out.println("[COORD-RESET] ACK barrier complete " + systemResetSnapshot());
        }
        return newlyAcknowledged;
    }

    public static synchronized String nextSystemResetComplete() {
        return nextSystemResetComplete(System.currentTimeMillis());
    }

    static synchronized String nextSystemResetComplete(long nowMillis) {
        String payload = resetCompletionOffer.nextValue(nowMillis);
        systemResetCompletionTransmissionStarted =
            resetCompletionOffer.isTransmissionStarted();
        lastSystemResetCompletionAttempt =
            resetCompletionOffer.getOfferCount();
        if (systemResetCompletionPending &&
            !resetCompletionOffer.isPending()) {
            systemResetCompletionPending = false;
            activeSystemResetId = "";
        }
        return payload;
    }

    public static synchronized boolean isSystemResetBlockingOrders() {
        return systemResetPendingExternalAck;
    }

    public static synchronized String systemResetState() {
        if (systemResetPendingExternalAck) {
            return "RESET_PENDING_EXTERNAL_ACK";
        }
        if (systemResetCompletionPending) {
            return "RESET_COMPLETE_PENDING_POS";
        }
        return "IDLE";
    }

    public static synchronized String systemResetSnapshot() {
        return "resetId=" +
            (activeSystemResetId.length() == 0 ?
                "none" : activeSystemResetId) +
            " state=" + systemResetState() +
            " m2Ack=" + m2SystemResetAcknowledged +
            " m3Ack=" + m3SystemResetAcknowledged +
            " m4Ack=" + m4SystemResetAcknowledged;
    }

    /** Records a validated alert without changing order execution. */
    public static boolean recordFtFaultAlert(String payload) {
        if (!admitFtEvidence(payload)) {
            return false;
        }
        latestFtFaultAlert = payload;
        ftVisualState = "FAULT_ALERT";
        updateFtVisualIdentity(payload, true);
        ftVisualSafeStop = "NOT_REQUESTED";
        ftVisualRecovery = "NOT_ACTIVE";
        queueFtVisualEvidence();
        return true;
    }

    /**
     * Holds new M1 order/batch dispatch while physical safe-stop evidence is
     * unavailable. This is coordination state only; it does not control a
     * machine actuator and therefore cannot establish FT_SAFE_STOP_ACK.
     */
    public static boolean recordFtSafeStopRequest(String payload) {
        if (!admitFtEvidence(payload)) {
            return false;
        }
        pendingFtSafeStopRequest = payload;
        ftCoordinationHold = true;
        ftSafeStopEstablished = false;
        ftVisualState = "FAULT_HOLD";
        updateFtVisualIdentity(payload, false);
        ftVisualSafeStop = "REQUESTED_HOLD_ACTIVE";
        ftVisualRecovery = "AWAITING_EVIDENCE";
        queueFtVisualEvidence();
        return true;
    }

    /** Records service-ready evidence but intentionally keeps the M1 hold. */
    public static boolean recordFtRecoveryReady(String payload) {
        if (!admitFtEvidence(payload)) {
            return false;
        }
        latestFtRecoveryReady = payload;
        ftVisualState = "RECOVERY_READY_HOLD";
        updateFtVisualIdentity(payload, false);
        ftVisualRecovery = "READY_AWAITING_M1";
        queueFtVisualEvidence();
        return true;
    }

    /** Records/escalates a failed recovery and retains the M1 hold. */
    public static boolean recordFtRecoveryFailed(String payload) {
        if (!admitFtEvidence(payload)) {
            return false;
        }
        latestFtRecoveryFailed = payload;
        ftCoordinationHold = true;
        ftSafeStopEstablished = false;
        ftVisualState = "RECOVERY_FAILED_HOLD";
        updateFtVisualIdentity(payload, false);
        ftVisualRecovery = "FAILED_HOLD_RETAINED";
        queueFtVisualEvidence();
        return true;
    }

    /** Takes one pending M1-only visual evidence snapshot, if available. */
    public static synchronized String takeFtVisualEvidence() {
        String evidence = pendingFtVisualEvidence;
        pendingFtVisualEvidence = null;
        return evidence;
    }

    /** Current display projection; useful for deterministic regression tests. */
    public static synchronized String visualFtEvidence() {
        return "V1|" + ftVisualState + "|" + ftVisualSource + "|" +
            ftVisualEvent + "|" + ftVisualSafeStop + "|" +
            ftVisualRecovery;
    }

    /**
     * False until a future approved interface supplies independent physical
     * safe-stop evidence. Status polling alone is not sufficient evidence.
     */
    public static boolean canSendFtSafeStopAck() {
        return ftSafeStopEstablished &&
            isPresentPayload(pendingFtSafeStopRequest);
    }

    public static String ftSnapshot() {
        return "hold=" + ftCoordinationHold +
            " safeStopEstablished=" + ftSafeStopEstablished +
            " batchTransitionHeld=" + ftBatchTransitionHeld +
            " hasAlert=" + isPresentPayload(latestFtFaultAlert) +
            " hasSafeStopRequest=" +
                isPresentPayload(pendingFtSafeStopRequest) +
            " hasRecoveryReady=" +
                isPresentPayload(latestFtRecoveryReady) +
            " hasRecoveryFailed=" +
                isPresentPayload(latestFtRecoveryFailed);
    }

    private static void updateFtVisualIdentity(
        String payload,
        boolean includesSubsystem
    ) {
        String[] fields = payload.split("\\|", -1);
        if (fields.length >= 2 && "V2".equals(fields[0])) {
            ftVisualEvent = safeVisualToken(fields[1], "unknown_event");
            if (includesSubsystem && fields.length >= 4) {
                ftVisualSource = safeVisualToken(
                    fields[3],
                    "M3_FAULT_SUPERVISOR"
                );
            }
        }
    }

    private static void queueFtVisualEvidence() {
        pendingFtVisualEvidence = visualFtEvidence();
    }

    private static String safeVisualToken(String value, String fallback) {
        if (value == null || value.trim().length() == 0) {
            return fallback;
        }
        return value.trim().replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    private static void loadCurrentProduct() {
        if (activeOrderV2 != null) {
            currentLiquidARatio =
                activeOrderV2.liquidARatios[currentProductIndex];
            currentLiquidBRatio =
                activeOrderV2.liquidBRatios[currentProductIndex];
            requiredBottles =
                activeOrderV2.quantities[currentProductIndex];
            currentSizeCode = activeOrderV2.sizeCodes[currentProductIndex];
            currentCapacityMl =
                activeOrderV2.capacitiesMl[currentProductIndex];
        }
        else {
            currentLiquidARatio =
                activeOrder.liquidARatios[currentProductIndex];
            currentLiquidBRatio =
                activeOrder.liquidBRatios[currentProductIndex];
            requiredBottles = activeOrder.quantities[currentProductIndex];
            currentSizeCode = OrderV2.SMALL;
            currentCapacityMl = OrderV2.SMALL_CAPACITY_ML;
        }
        completedBottles = 0;
        beginM4SimulationBatch();
        startOrderUntilMillis = System.currentTimeMillis() + 500L;
    }

    /**
     * Arms the simulation-only M4 batch trigger for the product that was just
     * loaded. This is an environmental side channel, so a payload it cannot
     * represent - for example an order ID that OrderV1 accepts but the
     * simulation transport does not - is reported and skipped. It must never
     * abort order acceptance or a product transition.
     */
    private static void beginM4SimulationBatch() {
        lastM4SimulationBatchAttempt = 0;
        m4SimulationBatchTransmissionStarted = false;
        String rejection = null;
        try {
            if (!m4SimulationBatchOffer.beginProductBatch(
                currentOrderId(),
                currentProductIndex + 1,
                requiredBottles,
                currentSizeCode,
                System.currentTimeMillis()
            )) {
                rejection = "conflicting quantity or size for " +
                    m4SimulationBatchOffer.getBatchId();
            }
        }
        catch (IllegalArgumentException invalid) {
            rejection = invalid.getMessage();
        }
        if (rejection != null) {
            m4SimulationBatchOffer.discard();
            System.out.println(
                "[M1-M4-SIM] batch trigger skipped for order " +
                currentOrderId() + " product " +
                (currentProductIndex + 1) + ": " + rejection
            );
        }
    }

    private static int activeProductCount() {
        if (activeOrderV2 != null) {
            return activeOrderV2.productCount;
        }
        return activeOrder == null ? 0 : activeOrder.productCount;
    }

    private static String parsedOrderId(String payload) {
        OrderV2 orderV2 = OrderV2.parse(payload);
        if (orderV2 != null) {
            return orderV2.orderId;
        }
        OrderV1 orderV1 = OrderV1.parse(payload);
        return orderV1 == null ? null : orderV1.orderId;
    }

    private static BoundedStringSignalOfferV1 newResetOffer() {
        return new BoundedStringSignalOfferV1(
            RESET_TRANSMISSION_ATTEMPTS,
            RESET_SIGNAL_HOLD_MILLIS,
            RESET_RETRY_MILLIS
        );
    }

    private static void clearM1OwnedRuntimeState(long nowMillis) {
        activeOrder = null;
        activeOrderV2 = null;
        currentProductIndex = 0;
        currentSizeCode = OrderV2.SMALL;
        currentCapacityMl = OrderV2.SMALL_CAPACITY_ML;
        currentLiquidARatio = 0;
        currentLiquidBRatio = 0;
        requiredBottles = 0;
        completedBottles = 0;
        orderActive = false;
        bottleDoneSignalLatched = false;
        bottleDoneRearmRequired = true;
        orderStartMillis = 0L;
        nextStatusPollMillis = nowMillis + 1000L;

        pendingCompletionPayload = "";
        completionPending = false;
        completionSendAfterMillis = 0L;
        completionTransmissionsRemaining = 0;
        lastCompletionAttempt = 0;
        completionSignalActive = false;
        completionSignalUntilMillis = 0L;
        completionTransmissionStarted = false;

        m4SimulationBatchOffer.discard();
        m4SimulationBatchTransmissionStarted = false;
        lastM4SimulationBatchAttempt = 0;

        latestFtFaultAlert = "";
        pendingFtSafeStopRequest = "";
        latestFtRecoveryReady = "";
        latestFtRecoveryFailed = "";
        ftCoordinationHold = false;
        ftSafeStopEstablished = false;
        ftBatchTransitionHeld = false;
        ftVisualState = "NORMAL";
        ftVisualSource = "M1_RESET";
        ftVisualEvent = activeSystemResetId;
        ftVisualSafeStop = "RESET_REQUESTED";
        ftVisualRecovery = "RESET_PENDING_EXTERNAL_ACK";
        queueFtVisualEvidence();

        loaderStatus = 0;
        conveyorStatus = 0;
        rotaryStatus = 0;
        fillerAStatus = 0;
        fillerBStatus = 0;
        lidStatus = 0;
        capperStatus = 0;
        unloaderStatus = 0;
        labellerStatus = 0;
        nextTwinContextMillis = 0L;
        startOrderUntilMillis = 0L;
    }

    static synchronized void resetForTest() {
        activeSystemResetId = "";
        lastSystemResetId = "";
        processedSystemResetIds.clear();
        lastAcceptedOrderId = "";
        acceptedOrderIds.clear();
        observedFtKeys.clear();
        retiredFtKeys.clear();
        m2ResetEpoch = 0;
        resetWatermark = java.math.BigInteger.valueOf(-1L);
        systemResetPendingExternalAck = false;
        systemResetCompletionPending = false;
        m2SystemResetAcknowledged = false;
        m3SystemResetAcknowledged = false;
        m4SystemResetAcknowledged = false;
        lastSystemResetCompletionAttempt = 0;
        systemResetCompletionTransmissionStarted = false;
        m2ResetOffer.discard();
        m3ResetOffer.discard();
        m4ResetOffer.discard();
        visualisationResetOffer.discard();
        resetCompletionOffer.discard();
        clearM1OwnedRuntimeState(0L);
        bottleDoneRearmRequired = false;
        ftVisualSource = "M3_FAULT_SUPERVISOR";
        ftVisualEvent = "none";
        ftVisualSafeStop = "NOT_REQUESTED";
        ftVisualRecovery = "NOT_ACTIVE";
        queueFtVisualEvidence();
    }

    private static boolean isValidResetId(String resetId) {
        return resetId != null && resetId.matches("RST[0-9]{4,}");
    }

    /** Read-only recipe context for the twin; repeating a stable payload is idempotent. */
    public static synchronized String nextTwinBatchContext() {
        long now = System.currentTimeMillis();
        if (!orderActive || systemResetPendingExternalAck || now < nextTwinContextMillis) return null;
        nextTwinContextMillis = now + 250L;
        return "V1|" + currentOrderId() + "|" + currentProductId() + "|" +
            currentLiquidARatio + "|" + currentLiquidBRatio + "|" + requiredBottles + "|" + currentSizeCode();
    }

    /** One held quantity window; no anonymous retry after a batch may finish. */
    public static synchronized boolean publishStartOrder() {
        return orderActive && !systemResetPendingExternalAck &&
            System.currentTimeMillis() < startOrderUntilMillis;
    }

    private static boolean isPresentPayload(String payload) {
        return payload != null && payload.trim().length() > 0;
    }

    /** Preserve correlation tombstones across reset; delayed FT cannot restore HOLD. */
    private static synchronized boolean admitFtEvidence(String payload) {
        if (!isPresentPayload(payload)) return false;
        String[] fields = payload.split("\\|", -1);
        String key = fields.length >= 3 && "V2".equals(fields[0]) ? fields[2] + "|" + fields[1] : payload;
        if (systemResetPendingExternalAck) {
            retiredFtKeys.add(key);
            return false;
        }
        if (retiredFtKeys.contains(key)) return false;
        if (fields.length >= 3 && "V2".equals(fields[0]) && fields[2].matches("E01(?:R[0-9]+)?")) {
            java.math.BigInteger epoch = "E01".equals(fields[2]) ? java.math.BigInteger.ZERO :
                new java.math.BigInteger(fields[2].substring(4));
            if (epoch.compareTo(java.math.BigInteger.valueOf(m2ResetEpoch)) < 0) return false;
        }
        observedFtKeys.add(key);
        return true;
    }
}
