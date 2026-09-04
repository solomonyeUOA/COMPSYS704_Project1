/** Small transport window for an idempotent bottle-correlated event. */
public final class M4BoundedEventV1 {
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
}
