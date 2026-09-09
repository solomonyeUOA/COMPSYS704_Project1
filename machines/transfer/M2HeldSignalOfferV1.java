/** Bottle-correlated bounded PRESENT windows with an explicit ABSENT gap. */
public final class M2HeldSignalOfferV1 {
    private final int maximumOffers;
    private final long holdMillis;
    private final long gapMillis;
    private String bottleId;
    private String payload;
    private int offers;
    private boolean holding;
    private long holdUntil;
    private long nextOfferAt;

    public M2HeldSignalOfferV1(int maximumOffers, long holdMillis, long gapMillis) {
        if (maximumOffers < 1 || holdMillis < 1 || gapMillis < 1) {
            throw new IllegalArgumentException("offer count, hold and gap must be positive");
        }
        this.maximumOffers = maximumOffers;
        this.holdMillis = holdMillis;
        this.gapMillis = gapMillis;
    }

    public boolean arm(String bottleId, String payload, long nowMillis) {
        if (bottleId == null || bottleId.length() == 0 ||
            payload == null || payload.length() == 0) { return false; }
        if (this.payload != null) {
            return this.bottleId.equals(bottleId) && this.payload.equals(payload);
        }
        this.bottleId = bottleId;
        this.payload = payload;
        offers = 0;
        holding = false;
        nextOfferAt = nowMillis;
        return true;
    }

    public String nextReactionValue(long nowMillis) {
        if (payload == null) { return null; }
        if (holding) {
            if (nowMillis < holdUntil) { return payload; }
            holding = false;
            if (offers >= maximumOffers) { clear(); }
            else { nextOfferAt = nowMillis + gapMillis; }
            return null;
        }
        if (nowMillis < nextOfferAt) { return null; }
        offers++;
        holding = true;
        holdUntil = nowMillis + holdMillis;
        return payload;
    }

    public boolean acknowledge(String bottleId) {
        if (payload == null || !this.bottleId.equals(bottleId)) { return false; }
        clear();
        return true;
    }

    public boolean isActive() { return payload != null; }

    private void clear() {
        bottleId = null;
        payload = null;
        offers = 0;
        holding = false;
        holdUntil = 0L;
        nextOfferAt = 0L;
    }
}
