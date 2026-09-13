/** Small transport window for an idempotent bottle-correlated event. */
public final class M4BoundedEventV1 {
    private static final int LOCAL_CONTROL_COPY_COUNT = 10;
    private static final long LOCAL_CONTROL_COPY_GAP_MS = 100L;

    private final int copyCount;
    private final long copyGapMs;
    private String payload;
    private int remaining;
    private long nextCopyMs;

    public M4BoundedEventV1(int copyCount, long copyGapMs) {
        if (copyCount <= 0 || copyGapMs < 0) {
            throw new IllegalArgumentException("invalid event window");
        }
        this.copyCount = copyCount;
        this.copyGapMs = copyGapMs;
    }

    /**
     * Controller/Plant traffic must span ordinary multi-CD scheduling pauses,
     * while remaining bounded below the 2.5 s operation timeout.  This is the
     * same proven 10 x 100 ms window used by the M4 reset acknowledgement.
     */
    public static M4BoundedEventV1 newLocalControlEvent() {
        return new M4BoundedEventV1(
            LOCAL_CONTROL_COPY_COUNT,
            LOCAL_CONTROL_COPY_GAP_MS
        );
    }

    public void publish(String value, long nowMs) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("event payload is required");
        }
        if (payload != null && payload.equals(value)) {
            return;
        }
        payload = value;
        remaining = copyCount;
        nextCopyMs = nowMs;
    }

    public String take(long nowMs) {
        if (payload == null || remaining <= 0 || nowMs < nextCopyMs) {
            return null;
        }
        String result = payload;
        remaining--;
        nextCopyMs = nowMs + copyGapMs;
        if (remaining == 0) {
            payload = null;
        }
        return result;
    }

    /** Read-only check; does not consume or reschedule a transport copy. */
    public boolean isPending() {
        return remaining > 0;
    }

    public void cancel() {
        payload = null;
        remaining = 0;
        nextCopyMs = 0L;
    }
}
