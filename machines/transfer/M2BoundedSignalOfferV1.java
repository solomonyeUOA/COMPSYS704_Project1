/**
 * Retains one bottle-correlated hand-off for a bounded number of PRESENT
 * windows. Every window carries the identical payload and consecutive
 * windows are separated by a real ABSENT gap.
 */
public final class M2BoundedSignalOfferV1 {
    private final int maximumOffers;
    private final long presentWindowMillis;
    private final long absentGapMillis;
    private String bottleId;
    private String payload;
    private int offerCount;
    private boolean presentWindowActive;
    private long presentUntilMillis;
    private long nextPresentAtMillis;

    public M2BoundedSignalOfferV1(
        int maximumOffers,
        long presentWindowMillis,
        long absentGapMillis
    ) {
        if (maximumOffers < 1) {
            throw new IllegalArgumentException(
                "maximumOffers must be positive"
            );
        }
        if (presentWindowMillis < 1 || absentGapMillis < 1) {
            throw new IllegalArgumentException(
                "PRESENT window and ABSENT gap must be positive"
            );
        }
        this.maximumOffers = maximumOffers;
        this.presentWindowMillis = presentWindowMillis;
        this.absentGapMillis = absentGapMillis;
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
        presentWindowActive = false;
        presentUntilMillis = 0L;
        nextPresentAtMillis = nowMillis;
        return true;
    }

    public String nextReactionValue() {
        return nextReactionValue(System.currentTimeMillis());
    }

    String nextReactionValue(long nowMillis) {
        if (payload == null) {
            return null;
        }
        if (presentWindowActive) {
            if (nowMillis < presentUntilMillis) {
                return payload;
            }
            presentWindowActive = false;
            nextPresentAtMillis = nowMillis + absentGapMillis;
            if (offerCount >= maximumOffers) {
                clear();
            }
            return null;
        }
        if (nowMillis < nextPresentAtMillis) {
            return null;
        }
        if (offerCount >= maximumOffers) {
            clear();
            return null;
        }
        offerCount++;
        presentWindowActive = true;
        presentUntilMillis = nowMillis + presentWindowMillis;
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
        presentWindowActive = false;
        presentUntilMillis = 0L;
        nextPresentAtMillis = 0L;
    }
}
