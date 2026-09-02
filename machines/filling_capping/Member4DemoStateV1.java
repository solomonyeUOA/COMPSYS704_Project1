/** Scheduler for the test-only S then L M4 SystemJ demonstration. */
public final class Member4DemoStateV1 {
    public static final int NONE = 0;
    public static final int RECOGNISE = 1;
    public static final int FILL = 2;
    public static final int CAP = 3;
    public static final int SORT_PACK = 4;
    public static final int PASS = 5;

    private static int bottleIndex;
    private static int phase;
    private static long nextActionMs;
    private static String activeContext;
    private static boolean passPublished;
    private static long nextRecipeMs;
    private static long nextPollMs;

    private Member4DemoStateV1() {
    }

    public static synchronized void reset() {
        bottleIndex = 0;
        phase = FILL;
        nextActionMs = System.currentTimeMillis() + 500L;
        activeContext = contextForIndex();
        passPublished = false;
        nextRecipeMs = 0L;
        nextPollMs = 0L;
    }

    public static synchronized int nextAction() {
        long now = System.currentTimeMillis();
        if (now < nextActionMs) {
            return NONE;
        }
        nextActionMs = now + 120L;
        if (phase == SORT_PACK &&
            Member4MachineStateV1.sortPackSnapshot().contains(
                "bottle=" + bottleId()
            ) &&
            Member4MachineStateV1.sortPackSnapshot().contains("status=DONE")) {
            if (bottleIndex == 0) {
                bottleIndex = 1;
                phase = FILL;
                activeContext = contextForIndex();
                return NONE;
            }
            if (!passPublished) {
                passPublished = true;
                return PASS;
            }
            return NONE;
        }
        return phase;
    }

    public static synchronized String recognitionRequest() {
        return bottleId() + "|" + (bottleIndex == 0 ? "S" : "L");
    }

    public static synchronized String recognitionResult() {
        return bottleId() + (bottleIndex == 0 ? "|S|200" : "|L|500");
    }

    public static synchronized String context() {
        return activeContext;
    }

    public static synchronized boolean shouldSendRecipe() {
        long now = System.currentTimeMillis();
        if (now < nextRecipeMs) {
            return false;
        }
        nextRecipeMs = now + 250L;
        return true;
    }

    public static synchronized boolean shouldPoll() {
        long now = System.currentTimeMillis();
        if (now < nextPollMs) {
            return false;
        }
        nextPollMs = now + 500L;
        return true;
    }

    public static synchronized void onContext(String context) {
        M4BottleContextV1 parsed = M4BottleContextV1.parse(context);
        if (parsed.getBottleId().equals(bottleId())) {
            activeContext = parsed.encode();
            phase = FILL;
            nextActionMs = System.currentTimeMillis() + 120L;
        }
    }

    public static synchronized void onFilled(String bottleId) {
        if (bottleId().equals(bottleId)) {
            phase = CAP;
            nextActionMs = System.currentTimeMillis() + 120L;
        }
    }

    public static synchronized void onCapped(String bottleId) {
        if (bottleId().equals(bottleId)) {
            phase = SORT_PACK;
            nextActionMs = System.currentTimeMillis() + 120L;
        }
    }

    public static void exitSuccess() {
        System.exit(0);
    }

    private static String bottleId() {
        return bottleIndex == 0 ? "M4-DEMO-S" : "M4-DEMO-L";
    }

    private static String contextForIndex() {
        return bottleIndex == 0 ?
            "M4-DEMO-S|S|200|GEOM_S|PACK_S" :
            "M4-DEMO-L|L|500|GEOM_L|PACK_L";
    }
}
