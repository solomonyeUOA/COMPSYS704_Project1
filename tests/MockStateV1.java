/** Test-only mutable state for the unified MockControllerCD. */
public final class MockStateV1 {
    private MockStateV1() {
    }

    public static int loaderStatus = 1;
    public static int transportStatus = 1;
    public static int fillerAStatus = 1;
    public static int fillerBStatus = 1;
    public static int lidStatus = 1;
    public static int capperStatus = 1;

    public static int liquidARatio = 0;
    public static int liquidBRatio = 0;
    public static int requiredBottles = 0;
    public static int emittedBottleDone = 0;
    public static int bottleTicks = 0;
    public static long lastStartMillis = 0;

    /** Returns false for a near-immediate transport duplicate. */
    public static boolean startOrder(int quantity) {
        long now = System.currentTimeMillis();
        if (quantity == requiredBottles && now - lastStartMillis < 500) {
            return false;
        }

        lastStartMillis = now;
        requiredBottles = quantity;
        emittedBottleDone = 0;
        bottleTicks = 0;
        setAllStatuses(2);
        return true;
    }

    /** Returns true once per simulated completed bottle. */
    public static boolean advanceBottleTick() {
        if (requiredBottles <= 0 || emittedBottleDone >= requiredBottles) {
            return false;
        }

        bottleTicks++;
        if (bottleTicks < 2000) {
            return false;
        }

        bottleTicks = 0;
        emittedBottleDone++;
        if (emittedBottleDone == requiredBottles) {
            setAllStatuses(3);
        }
        return true;
    }

    private static void setAllStatuses(int status) {
        loaderStatus = status;
        transportStatus = status;
        fillerAStatus = status;
        fillerBStatus = status;
        lidStatus = status;
        capperStatus = status;
    }
}
