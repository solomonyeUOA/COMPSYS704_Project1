import java.util.Locale;

/**
 * Bounded, idempotent transport for the simulation-only M1 -> M4 batch
 * request. A logical product batch owns one stable payload. Retries never
 * regenerate its identity, and every PRESENT pulse is followed by an ABSENT
 * reaction before another copy can be offered.
 */
public final class M1SimulationBatchOfferV1 {
    private final int maximumOffers;
    private final long retryIntervalMillis;
    private String batchId;
    private int quantity;
    private String payload;
    private int offerCount;
    private boolean absentReactionRequired;
    private long nextOfferAtMillis;

    public M1SimulationBatchOfferV1(
        int maximumOffers,
        long retryIntervalMillis
    ) {
        if (maximumOffers < 1) {
            throw new IllegalArgumentException(
                "maximumOffers must be positive"
            );
        }
        if (retryIntervalMillis < 1L) {
            throw new IllegalArgumentException(
                "retryIntervalMillis must be positive"
            );
        }
        this.maximumOffers = maximumOffers;
        this.retryIntervalMillis = retryIntervalMillis;
    }

    /**
     * Starts the transport window for one Coordinator-owned product batch.
     * Calling this again with the same identity and quantity is idempotent.
     * A different identity is valid only because the Coordinator calls this
     * method at a confirmed product lifecycle boundary.
     */
    public boolean beginProductBatch(
        String orderId,
        int oneBasedProductIndex,
        int requestedQuantity,
        long nowMillis
    ) {
        validateOrderId(orderId);
        if (oneBasedProductIndex < 1 || oneBasedProductIndex > 99) {
            throw new IllegalArgumentException(
                "product index must be 1..99"
            );
        }
        if (requestedQuantity < 1) {
            throw new IllegalArgumentException("quantity must be positive");
        }

        String requestedBatchId = orderId + "-P" + String.format(
            Locale.ROOT,
            "%02d",
            Integer.valueOf(oneBasedProductIndex)
        );
        if (requestedBatchId.equals(batchId)) {
            return requestedQuantity == quantity;
        }

        batchId = requestedBatchId;
        quantity = requestedQuantity;
        payload = batchId + "|" + quantity;
        offerCount = 0;
        absentReactionRequired = false;
        nextOfferAtMillis = nowMillis;
        return true;
    }

    public String nextReactionValue(long nowMillis) {
        if (payload == null) {
            return null;
        }
        if (absentReactionRequired) {
            absentReactionRequired = false;
            if (offerCount >= maximumOffers) {
                payload = null;
            }
            return null;
        }
        if (offerCount >= maximumOffers) {
            payload = null;
            return null;
        }
        if (nowMillis < nextOfferAtMillis) {
            return null;
        }
        offerCount++;
        absentReactionRequired = true;
        nextOfferAtMillis = nowMillis + retryIntervalMillis;
        return batchId + "|" + quantity;
    }

    /**
     * Drops any pending offer and its identity. Used when the Coordinator
     * cannot establish a batch for the product it has just loaded, so that a
     * previous product's payload can never be published in its place.
     */
    public void discard() {
        batchId = null;
        quantity = 0;
        payload = null;
        offerCount = 0;
        absentReactionRequired = false;
        nextOfferAtMillis = 0L;
    }

    public String getBatchId() {
        return batchId;
    }

    public String getStablePayload() {
        return batchId == null ? null : batchId + "|" + quantity;
    }

    public int getOfferCount() {
        return offerCount;
    }

    public boolean isPending() {
        return payload != null;
    }

    private static void validateOrderId(String orderId) {
        if (orderId == null || orderId.length() == 0 ||
            !orderId.equals(orderId.trim()) || orderId.indexOf('|') >= 0) {
            throw new IllegalArgumentException("invalid orderId");
        }
        for (int index = 0; index < orderId.length(); index++) {
            char character = orderId.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                throw new IllegalArgumentException(
                    "orderId must be printable ASCII without spaces"
                );
            }
        }
    }
}
