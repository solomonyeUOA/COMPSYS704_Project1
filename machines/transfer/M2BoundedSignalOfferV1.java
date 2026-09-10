/**
 * Retains one bottle-correlated hand-off for a bounded number of pulses.
 * Every pulse carries the identical payload, and a pulse is always followed
 * by an ABSENT reaction before another retry can be emitted.
 */
public final class M2BoundedSignalOfferV1 {
    private final int maximumOffers;
    private final long retryIntervalMillis;
    private String bottleId;
    private String payload;
    private int offerCount;
    private boolean absentReactionRequired;
    private long nextOfferAtMillis;

    public M2BoundedSignalOfferV1(
        int maximumOffers,
        long retryIntervalMillis
    ) {
        if (maximumOffers < 1) {
            throw new IllegalArgumentException(
                "maximumOffers must be positive"
            );
        }
        if (retryIntervalMillis < 1) {
            throw new IllegalArgumentException(
                "retryIntervalMillis must be positive"
            );
        }
        this.maximumOffers = maximumOffers;
        this.retryIntervalMillis = retryIntervalMillis;
    }

    public boolean arm(String bottleId, String payload) {
        return arm(bottleId, payload, System.currentTimeMillis());
    }

    boolean arm(String bottleId, String payload, long nowMillis) {
        if (bottleId == null || bottleId.length() == 0 || payload == null ||
            payload.length() == 0) {
            return false;
        }
        if (this.payload != null) {
            return this.bottleId.equals(bottleId) &&
                this.payload.equals(payload);
        }
        this.bottleId = bottleId;
        this.payload = payload;
        offerCount = 0;
        absentReactionRequired = false;
        nextOfferAtMillis = nowMillis;
        return true;
    }

    public String nextReactionValue() {
        return nextReactionValue(System.currentTimeMillis());
    }

    String nextReactionValue(long nowMillis) {
        if (payload == null) {
            return null;
        }
        if (absentReactionRequired) {
            absentReactionRequired = false;
            if (offerCount >= maximumOffers) {
                clear();
            }
            return null;
        }
        if (nowMillis < nextOfferAtMillis) {
            return null;
        }
        if (offerCount >= maximumOffers) {
            clear();
            return null;
        }
        offerCount++;
        absentReactionRequired = true;
        nextOfferAtMillis = nowMillis + retryIntervalMillis;
        return payload;
    }

    /** Cancels remaining copies only for the matching bottle identity. */
    public boolean acknowledge(String acknowledgedBottleId) {
        if (payload == null ||
            !bottleId.equals(acknowledgedBottleId)) {
            return false;
        }
        clear();
        return true;
    }

    public boolean isActive() {
        return payload != null;
    }

    public int getOfferCount() {
        return offerCount;
    }

    private void clear() {
        bottleId = null;
        payload = null;
        offerCount = 0;
        absentReactionRequired = false;
        nextOfferAtMillis = 0L;
    }
}
