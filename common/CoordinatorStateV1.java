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

    public static OrderV1 activeOrder = null;
    public static int currentProductIndex = 0;
    public static int currentLiquidARatio = 0;
    public static int currentLiquidBRatio = 0;
    public static int requiredBottles = 0;
    public static int completedBottles = 0;
    public static boolean orderActive = false;
    public static boolean bottleDoneSignalLatched = false;
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
        if (orderActive || ftCoordinationHold) {
            return false;
        }

        OrderV1 parsedOrder = OrderV1.parse(payload);
        if (parsedOrder == null) {
            return false;
        }
        if (parsedOrder.orderId.equals(lastAcceptedOrderId)) {
            return false;
        }

        activeOrder = parsedOrder;
        lastAcceptedOrderId = parsedOrder.orderId;
        currentProductIndex = 0;
        loadCurrentProduct();
        orderActive = true;
        orderStartMillis = System.currentTimeMillis();
        return true;
    }

    /** Returns true when the current product has received all BOTTLE_DONEs. */
    public static boolean recordBottleDone() {
        if (!orderActive) {
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
        return activeOrder != null &&
            currentProductIndex + 1 < activeOrder.productCount;
    }

    public static void advanceToNextProduct() {
        currentProductIndex++;
        loadCurrentProduct();
    }

    public static String currentProductId() {
        return activeOrder.productIds[currentProductIndex];
    }

    /** Current stable simulation-only batch identity. */
    public static String currentM4SimulationBatchId() {
        return m4SimulationBatchOffer.getBatchId();
    }

    /** Current stable batchId|quantity payload, including after retries drain. */
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
        pendingCompletionPayload = activeOrder.orderId + "|COMPLETED|" +
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
        OrderV1 parsedOrder = OrderV1.parse(payload);
        return parsedOrder != null &&
            parsedOrder.orderId.equals(lastAcceptedOrderId);
    }

    public static String lifecycleSnapshot() {
        String orderId = activeOrder == null ?
            "none" : activeOrder.orderId;
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
            " ftHold=" + ftCoordinationHold +
            " ftSafeStopEstablished=" + ftSafeStopEstablished;
    }

    /** Records a validated alert without changing order execution. */
    public static boolean recordFtFaultAlert(String payload) {
        if (!isPresentPayload(payload)) {
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
        if (!isPresentPayload(payload)) {
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
        if (!isPresentPayload(payload)) {
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
        if (!isPresentPayload(payload)) {
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
        currentLiquidARatio =
            activeOrder.liquidARatios[currentProductIndex];
        currentLiquidBRatio =
            activeOrder.liquidBRatios[currentProductIndex];
        requiredBottles = activeOrder.quantities[currentProductIndex];
        completedBottles = 0;
        beginM4SimulationBatch();
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
                activeOrder.orderId,
                currentProductIndex + 1,
                requiredBottles,
                System.currentTimeMillis()
            )) {
                rejection = "conflicting quantity for " +
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
                activeOrder.orderId + " product " +
                (currentProductIndex + 1) + ": " + rejection
            );
        }
    }

    private static boolean isPresentPayload(String payload) {
        return payload != null && payload.trim().length() > 0;
    }
}
