/**
 * Shared order/status state for the single CoordinatorCD instance.
 *
 * The course SystemJ compiler does not safely lift ordinary local variables
 * referenced by separate parallel branches. Keeping the data here lets the
 * .sysj source retain independent, non-blocking listeners without putting any
 * machine-level behaviour in the Coordinator.
 */
public final class CoordinatorStateV1 {
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
    public static long orderStartMillis = 0;
    public static long nextStatusPollMillis =
        System.currentTimeMillis() + 1000;
    public static String pendingCompletionPayload = "";
    public static boolean completionPending = false;
    public static long completionSendAfterMillis = 0;
    public static int completionTransmissionsRemaining = 0;

    /** Parses and accepts a new order only when no order is active. */
    public static boolean accept(String payload) {
        if (orderActive || completionPending) {
            return false;
        }

        OrderV1 parsedOrder = OrderV1.parse(payload);
        if (parsedOrder == null) {
            return false;
        }

        activeOrder = parsedOrder;
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
        completionTransmissionsRemaining = 3;
        completionSendAfterMillis = System.currentTimeMillis() + 250;
        nextStatusPollMillis = System.currentTimeMillis() + 1000;
    }

    /** Returns one transport copy of the same logical completion event. */
    public static String nextCompletionTransmission() {
        completionTransmissionsRemaining--;
        if (completionTransmissionsRemaining > 0) {
            completionSendAfterMillis = System.currentTimeMillis() + 500;
        }
        else {
            completionPending = false;
        }
        return pendingCompletionPayload;
    }

    private static void loadCurrentProduct() {
        currentLiquidARatio =
            activeOrder.liquidARatios[currentProductIndex];
        currentLiquidBRatio =
            activeOrder.liquidBRatios[currentProductIndex];
        requiredBottles = activeOrder.quantities[currentProductIndex];
        completedBottles = 0;
    }
}
