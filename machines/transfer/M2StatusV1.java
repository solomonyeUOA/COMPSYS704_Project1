/** Frozen Coordinator-facing status codes used by every M2 Controller. */
public final class M2StatusV1 {
    public static final int IDLE = 0;
    public static final int READY = 1;
    public static final int BUSY = 2;
    public static final int DONE = 3;
    public static final int FAULT = 4;

    private M2StatusV1() {
    }

    public static boolean isValid(int value) {
        return value >= IDLE && value <= FAULT;
    }

    public static String nameOf(int value) {
        switch (value) {
            case IDLE:
                return "IDLE";
            case READY:
                return "READY";
            case BUSY:
                return "BUSY";
            case DONE:
                return "DONE";
            case FAULT:
                return "FAULT";
            default:
                return "UNKNOWN";
        }
    }
}
