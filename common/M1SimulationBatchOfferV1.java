import java.util.Locale;

/**
 * Bounded, idempotent transport for the simulation-only M1 -> M4 batch
 * request. A logical product batch owns one stable payload. Retries never
 * regenerate its identity. Each copy stays PRESENT for 200 ms, followed by
 * a wall-clock ABSENT gap before another copy can be offered. A single
 * logical reaction is too brief for independently scheduled TCP receivers.
 */
public final class M1SimulationBatchOfferV1 {
    static final long SIGNAL_HOLD_MILLIS = 200L;
    private final BoundedStringSignalOfferV1 transport;
    private String batchId;
    private int quantity;
    private String sizeCode;

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
        transport = new BoundedStringSignalOfferV1(
            maximumOffers, SIGNAL_HOLD_MILLIS, retryIntervalMillis
        );
    }

    /**
     * Starts the transport window for one Coordinator-owned product batch.
     * Calling this again with the same identity, quantity and size is
     * idempotent. A quantity or size conflict never mutates the stable offer.
     * A different identity is valid only because the Coordinator calls this
     * method at a confirmed product lifecycle boundary.
     */
    public boolean beginProductBatch(
        String orderId,
        int oneBasedProductIndex,
        int requestedQuantity,
        String requestedSizeCode,
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
        if (OrderV2.capacityFor(requestedSizeCode) == 0) {
            throw new IllegalArgumentException("sizeCode must be S or L");
        }

        String requestedBatchId = orderId + "-P" + String.format(
            Locale.ROOT,
            "%02d",
            Integer.valueOf(oneBasedProductIndex)
        );
        if (requestedBatchId.equals(batchId)) {
            return requestedQuantity == quantity &&
                requestedSizeCode.equals(sizeCode);
        }

        batchId = requestedBatchId;
        quantity = requestedQuantity;
        sizeCode = requestedSizeCode;
        // This is a confirmed new product boundary, not a retry. Preserve
        // duplicate identity above even after the old transport has drained.
        transport.discard();
        transport.begin(getStablePayload(), nowMillis);
        return true;
    }

    /** Legacy source-compatible overload; an OrderV1 product defaults to S. */
    public boolean beginProductBatch(
        String orderId,
        int oneBasedProductIndex,
        int requestedQuantity,
        long nowMillis
    ) {
        return beginProductBatch(
            orderId,
            oneBasedProductIndex,
            requestedQuantity,
            OrderV2.SMALL,
            nowMillis
        );
    }

    public String nextReactionValue(long nowMillis) {
        return transport.nextValue(nowMillis);
    }

    /**
     * Drops any pending offer and its identity. Used when the Coordinator
     * cannot establish a batch for the product it has just loaded, so that a
     * previous product's payload can never be published in its place.
     */
    public void discard() {
        batchId = null;
        quantity = 0;
        sizeCode = null;
        transport.discard();
    }

    public String getBatchId() {
        return batchId;
    }

    public String getStablePayload() {
        return batchId == null ? null :
            batchId + "|" + quantity + "|" + sizeCode;
    }

    public int getOfferCount() {
        return transport.getOfferCount();
    }

    public boolean isPending() {
        return transport.isPending();
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
