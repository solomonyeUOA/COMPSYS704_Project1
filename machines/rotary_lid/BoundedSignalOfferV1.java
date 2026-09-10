/**
 * Produces a bounded sequence of PRESENT windows separated by ABSENT gaps.
 * Repeated copies retain exactly the same payload for receiver de-duplication.
 */
public final class BoundedSignalOfferV1 {
    private final int maximumOffers;
    private final long presentWindowMs;
    private final long absentGapMs;
    private String payload;
    private String bottleId;
    private int offers;
    private boolean presentWindowActive;
    private long presentUntilMs;
    private long nextPresentAtMs;

    public BoundedSignalOfferV1(int maximumOffers) {
        this(maximumOffers, 500, 100);
    }

    public BoundedSignalOfferV1(
        int maximumOffers,
        long presentWindowMs,
        long absentGapMs
    ) {
        if (maximumOffers < 1) {
            throw new IllegalArgumentException("maximumOffers must be positive");
        }
        if (presentWindowMs < 1 || absentGapMs < 1) {
            throw new IllegalArgumentException("window and gap must be positive");
        }
        this.maximumOffers = maximumOffers;
        this.presentWindowMs = presentWindowMs;
        this.absentGapMs = absentGapMs;
    }

    public boolean arm(String bottleId, String payload) {
        return arm(bottleId, payload, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    boolean arm(String bottleId, String payload, long nowMs) {
        if (bottleId == null || bottleId.length() == 0 || payload == null) {
            return false;
        }
        if (this.payload != null) {
            return this.bottleId.equals(bottleId) && this.payload.equals(payload);
        }
        this.bottleId = bottleId;
        this.payload = payload;
        offers = 0;
        presentWindowActive = false;
        presentUntilMs = 0;
        nextPresentAtMs = nowMs;
        return true;
    }

    public String nextReactionValue() {
        return nextReactionValue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    String nextReactionValue(long nowMs) {
        if (payload == null) {
            return null;
        }
        if (presentWindowActive) {
            if (nowMs < presentUntilMs) {
                return payload;
            }
            presentWindowActive = false;
            nextPresentAtMs = nowMs + absentGapMs;
            if (offers >= maximumOffers) {
                clear();
            }
            return null;
        }
        if (nowMs < nextPresentAtMs) {
            return null;
        }
        if (offers >= maximumOffers) {
            clear();
            return null;
        }
        offers++;
        presentWindowActive = true;
        presentUntilMs = nowMs + presentWindowMs;
        return payload;
    }

    public boolean acknowledge(String bottleId) {
        if (payload == null || !this.bottleId.equals(bottleId)) {
            return false;
        }
        clear();
        return true;
    }

    public boolean isActive() {
        return payload != null;
    }

    public int getOfferCount() {
        return offers;
    }

    private void clear() {
        payload = null;
        bottleId = null;
        offers = 0;
        presentWindowActive = false;
        presentUntilMs = 0;
        nextPresentAtMs = 0;
    }
}
