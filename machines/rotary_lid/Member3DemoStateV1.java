/** Scheduler state for the test-only integrated SystemJ M3 demonstration. */
public final class Member3DemoStateV1 {
    public static final int NONE = 0;
    public static final int SEND_CONTEXT = 1;
    public static final int LOAD = 2;
    public static final int CLEAR_P6 = 3;
    public static final int PASS = 4;

    private static int phase;
    private static long actionAfterMs;
    private static String clearBottleId;
    private static boolean passPublished;
    private static long contextStartMs;
    private static long nextStatusPollMs;

    private Member3DemoStateV1() {
    }

    public static synchronized void reset() {
        phase = 0;
        contextStartMs = System.currentTimeMillis();
        actionAfterMs = contextStartMs + 500;
        clearBottleId = null;
        passPublished = false;
        nextStatusPollMs = contextStartMs;
    }

    public static synchronized int nextAction() {
        long now = System.currentTimeMillis();
        if (now < actionAfterMs) {
            return NONE;
        }
        if (phase == 0) {
            actionAfterMs = now + 100;
            if (now - contextStartMs >= 1500) {
                phase = 1;
                return NONE;
            }
            return SEND_CONTEXT;
        }
        if (phase == 1) {
            actionAfterMs = now + 100;
            if (Member3PlantStateV1.positionLabel(0).contains("DEMO-B001")) {
                phase = 2;
                return NONE;
            }
            return LOAD;
        }
        if (phase == 2 && clearBottleId != null) {
            phase = 3;
            actionAfterMs = now + 100;
            return CLEAR_P6;
        }
        if (phase == 3 && allPositionsEmpty() && !passPublished) {
            passPublished = true;
            return PASS;
        }
        actionAfterMs = now + 50;
        return NONE;
    }

    public static synchronized void onLabelOffered(String bottleId) {
        BottleContextV1.validateBottleId(bottleId);
        clearBottleId = bottleId;
        actionAfterMs = System.currentTimeMillis() + 150;
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
}
