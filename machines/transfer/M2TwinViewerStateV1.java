/** Throttles the read-only Digital Twin console viewer. */
public final class M2TwinViewerStateV1 {
    private static long nextRequestAtMillis;

    private M2TwinViewerStateV1() {
    }

    public static synchronized boolean shouldRequest(long nowMillis) {
        if (nowMillis < nextRequestAtMillis) {
            return false;
        }
        nextRequestAtMillis = nowMillis + 1000L;
        return true;
    }
}
