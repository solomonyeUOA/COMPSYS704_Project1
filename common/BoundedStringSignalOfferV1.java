/**
 * Bounded reliable transport state for a String-valued SystemJ signal.
 *
 * Each logical copy remains PRESENT for a wall-clock hold window. Copies are
 * separated by an ABSENT gap, and all retries carry the identical payload.
 */
public final class BoundedStringSignalOfferV1 {
    private final int maximumOffers;
    private final long holdMillis;
    private final long retryGapMillis;

    private String stablePayload;
    private boolean pending;
    private boolean signalActive;
    private long signalUntilMillis;
    private long nextOfferAtMillis;
    private int offerCount;
    private boolean transmissionStarted;

    public BoundedStringSignalOfferV1(
        int offers,
        long signalHoldMillis,
        long retryMillis
    ) {
        if (offers < 1 || signalHoldMillis < 1L || retryMillis < 1L) {
            throw new IllegalArgumentException(
                "offers, hold and retry values must be positive"
            );
        }
        maximumOffers = offers;
        holdMillis = signalHoldMillis;
        retryGapMillis = retryMillis;
    }

    /** Begins one logical transport; an identical active begin is idempotent. */
    public synchronized boolean begin(String payload, long nowMillis) {
        if (payload == null || payload.trim().length() == 0) {
            throw new IllegalArgumentException("payload is required");
        }
        if (pending) {
            return payload.equals(stablePayload);
        }
        stablePayload = payload;
        pending = true;
        signalActive = false;
        signalUntilMillis = 0L;
        nextOfferAtMillis = nowMillis;
        offerCount = 0;
        transmissionStarted = false;
        return true;
    }

    /** Returns the current PRESENT value, or null for an ABSENT reaction. */
    public synchronized String nextValue(long nowMillis) {
        transmissionStarted = false;
        if (!pending) {
            return null;
        }
        if (signalActive) {
            if (nowMillis < signalUntilMillis) {
                return stablePayload;
            }
            signalActive = false;
            signalUntilMillis = 0L;
            if (offerCount >= maximumOffers) {
                pending = false;
            }
            else {
                nextOfferAtMillis = nowMillis + retryGapMillis;
            }
            return null;
        }
        if (nowMillis < nextOfferAtMillis) {
            return null;
        }

        offerCount++;
        signalActive = true;
        signalUntilMillis = nowMillis + holdMillis;
        transmissionStarted = true;
        return stablePayload;
    }

    public synchronized void discard() {
        stablePayload = null;
        pending = false;
        signalActive = false;
        signalUntilMillis = 0L;
        nextOfferAtMillis = 0L;
        offerCount = 0;
        transmissionStarted = false;
    }

    public synchronized String getStablePayload() {
        return stablePayload;
    }

    public synchronized int getOfferCount() {
        return offerCount;
    }

    public synchronized boolean isPending() {
        return pending;
    }

    public synchronized boolean isTransmissionStarted() {
        return transmissionStarted;
    }
}
