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
            " ftHold=" + ftCoordinationHold +
            " ftSafeStopEstablished=" + ftSafeStopEstablished;
    }

    /** Records a validated alert without changing order execution. */
    public static boolean recordFtFaultAlert(String payload) {
        if (!isPresentPayload(payload)) {
            return false;
        }
        latestFtFaultAlert = payload;
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
        return true;
    }

    /** Records service-ready evidence but intentionally keeps the M1 hold. */
    public static boolean recordFtRecoveryReady(String payload) {
        if (!isPresentPayload(payload)) {
            return false;
        }
        latestFtRecoveryReady = payload;
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
        return true;
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

    private static void loadCurrentProduct() {
        currentLiquidARatio =
            activeOrder.liquidARatios[currentProductIndex];
        currentLiquidBRatio =
            activeOrder.liquidBRatios[currentProductIndex];
        requiredBottles = activeOrder.quantities[currentProductIndex];
        completedBottles = 0;
    }

    private static boolean isPresentPayload(String payload) {
        return payload != null && payload.trim().length() > 0;
    }
}
