/**
 * Bounded, idempotent transport for the formal M1 -> M4 batch contract.
 * A logical product batch owns one stable payload. Retries never
 * regenerate its identity. Each copy stays PRESENT for 200 ms, followed by
 * a wall-clock ABSENT gap before another copy can be offered. A single
 * logical reaction is too brief for independently scheduled TCP receivers.
 */
public final class M1M4BatchOfferV1 {
    static final long SIGNAL_HOLD_MILLIS = 200L;
    private final BoundedStringSignalOfferV1 transport;
    private String batchId;
    private int quantity;
    private String sizeCode;

    public M1M4BatchOfferV1(
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
        M1ProductBatchV1 batch = M1ProductBatchV1.forProduct(
            orderId,
            oneBasedProductIndex,
            requestedQuantity,
            requestedSizeCode
        );
        return beginBatch(
            batch.getBatchId(), requestedQuantity, requestedSizeCode, nowMillis
        );
    }

    /** Starts delivery for an already established Coordinator batch contract. */
    public boolean beginBatch(
        String requestedBatchId,
        int requestedQuantity,
        String requestedSizeCode,
        long nowMillis
    ) {
        M1ProductBatchV1.validateTransportId(requestedBatchId, "batchId");
        if (requestedQuantity < 1) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (OrderV2.capacityFor(requestedSizeCode) == 0) {
            throw new IllegalArgumentException("sizeCode must be S or L");
        }
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

}
