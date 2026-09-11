/** Scheduler state for the test-only integrated SystemJ M3 demonstration. */
public final class Member3DemoStateV1 {
    public static final int NONE = 0;
    public static final int SEND_CONTEXT = 1;
    public static final int LOAD = 2;
    public static final int CLEAR_P6 = 3;
    public static final int PASS = 4;

    private static int phase;
    private static String clearBottleId;
    private static boolean passPublished;
    private static long contextStartMs;
    private static long nextStatusPollMs;
    private static BoundedStringSignalOfferV1 contextOffer;
    private static BoundedStringSignalOfferV1 loadOffer;
    private static BoundedStringSignalOfferV1 fillConfirmationOffer;
    private static BoundedStringSignalOfferV1 capConfirmationOffer;
    private static BoundedStringSignalOfferV1 labelConfirmationOffer;
    private static BoundedStringSignalOfferV1 clearOffer;

    private Member3DemoStateV1() {
    }

    public static synchronized void reset() {
        phase = 0;
        contextStartMs = System.currentTimeMillis();
        clearBottleId = null;
        passPublished = false;
        nextStatusPollMs = contextStartMs;
        contextOffer = newOffer();
        loadOffer = newOffer();
        fillConfirmationOffer = newOffer();
        capConfirmationOffer = newOffer();
        labelConfirmationOffer = newOffer();
        clearOffer = newOffer();
        contextOffer.begin(
            "DEMO-B001|S|200|GEOM_S|PACK_S",
            contextStartMs
        );
    }

    public static synchronized int nextAction() {
        long now = System.currentTimeMillis();
        if (phase == 0) {
            if (now - contextStartMs >= 1500) {
                contextOffer.discard();
                phase = 1;
                loadOffer.begin("DEMO-B001", now);
                return NONE;
            }
            return contextOffer.nextValue(now) == null ? NONE : SEND_CONTEXT;
        }
        if (phase == 1) {
            if (Member3PlantStateV1.positionLabel(0).contains("DEMO-B001")) {
                loadOffer.discard();
                phase = 2;
                return NONE;
            }
            if (!loadOffer.isPending()) {
                loadOffer.begin("DEMO-B001", now);
            }
            return loadOffer.nextValue(now) == null ? NONE : LOAD;
        }
        if (phase == 2) {
            if (allPositionsEmpty()) {
                clearOffer.discard();
                phase = 3;
                return NONE;
            }
            if (clearBottleId != null &&
                Member3PlantStateV1.positionLabel(5).contains(
                    clearBottleId + "[FLCB]"
                )) {
                if (!clearOffer.isPending()) {
                    clearOffer.begin(clearBottleId, now);
                }
                return clearOffer.nextValue(now) == null ? NONE : CLEAR_P6;
            }
            return NONE;
        }
        if (phase == 3 && allPositionsEmpty() && !passPublished) {
            passPublished = true;
            return PASS;
        }
        return NONE;
    }

    public static synchronized boolean onLabelOffered(String bottleId) {
        BottleContextV1.validateBottleId(bottleId);
        clearBottleId = bottleId;
        return beginConfirmation(labelConfirmationOffer, bottleId);
    }

    public static synchronized boolean onFillOffered(String payload) {
        return beginConfirmation(
            fillConfirmationOffer,
            bottleIdFrom(payload)
        );
    }

    public static synchronized boolean onCapOffered(String payload) {
        return beginConfirmation(
            capConfirmationOffer,
            bottleIdFrom(payload)
        );
    }

    public static synchronized String nextFillConfirmation() {
        return nextConfirmation(fillConfirmationOffer, 1, "[F---]");
    }

    public static synchronized String nextCapConfirmation() {
        return nextConfirmation(capConfirmationOffer, 3, "[FLC-]");
    }

    public static synchronized String nextLabelConfirmation() {
        return nextConfirmation(labelConfirmationOffer, 5, "[FLCB]");
    }

    public static synchronized String clearBottleId() {
        return clearBottleId;
    }

    public static synchronized boolean shouldPollStatus() {
        long now = System.currentTimeMillis();
        if (now < nextStatusPollMs) {
            return false;
        }
        nextStatusPollMs = now + 250;
        return true;
    }

    private static boolean allPositionsEmpty() {
        for (int position = 0; position < 6; position++) {
            if (!"empty".equals(Member3PlantStateV1.positionLabel(position))) {
                return false;
            }
        }
        return true;
    }

    private static BoundedStringSignalOfferV1 newOffer() {
        return new BoundedStringSignalOfferV1(5, 300L, 100L);
    }

    private static boolean beginConfirmation(
        BoundedStringSignalOfferV1 offer,
        String bottleId
    ) {
        if (offer.isPending()) {
            return false;
        }
        offer.begin(bottleId, System.currentTimeMillis());
        return true;
    }

    private static String bottleIdFrom(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException("bottle payload is required");
        }
        String bottleId = payload.split("\\|", -1)[0];
        BottleContextV1.validateBottleId(bottleId);
        return bottleId;
    }

    private static String nextConfirmation(
        BoundedStringSignalOfferV1 offer,
        int position,
        String completedState
    ) {
        String bottleId = offer.getStablePayload();
        if (bottleId != null && Member3PlantStateV1.positionLabel(position)
            .equals(bottleId + completedState)) {
            offer.discard();
            return null;
        }
        return offer.nextValue(System.currentTimeMillis());
    }
}
