/** Shared status codes required by the frozen M1/M4 interface. */
public final class M4StatusV1 {
    public static final int IDLE = 0;
    public static final int READY = 1;
    public static final int BUSY = 2;
    public static final int DONE = 3;
    public static final int FAULT = 4;

    private M4StatusV1() {
    }

    public static String nameOf(int status) {
        switch (status) {
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
