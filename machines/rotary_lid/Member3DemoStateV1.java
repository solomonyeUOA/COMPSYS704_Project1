/** Deterministic action scheduler for the test-only SystemJ demo driver. */
public final class Member3DemoStateV1 {
    public static final int NONE = 0;
    public static final int LOAD = 1;
    public static final int ROTATE = 2;
    public static final int MARK_FILLED = 3;
    public static final int MARK_CAPPED = 4;
    public static final int REMOVE = 5;
    public static final int PASS = 6;

    private static int phase;
    private static long actionAfterMs;
    private static boolean complete;
    private static boolean sawRotationBusy;

    private Member3DemoStateV1() {
    }

    public static synchronized void reset() {
        phase = 0;
        actionAfterMs = System.currentTimeMillis() + 500;
        complete = false;
        sawRotationBusy = false;
    }

    public static synchronized int nextAction() {
        long now = System.currentTimeMillis();
        if (now < actionAfterMs || complete) {
            return NONE;
        }

        if (isWaitingForRotation()) {
            int status = Member3MachineStateV1.getTransportStatus();
            if (status == Member3MachineStateV1.BUSY) {
                sawRotationBusy = true;
            }
            else if (sawRotationBusy && status == Member3MachineStateV1.DONE) {
                advanceAfterRotation(now);
            }
        }

        switch (phase) {
            case 0:
                if (Member3PlantStateV1.positionLabel(0)
                    .contains("DEMO-B001")) {
                    phase = 1;
                    actionAfterMs = now + 150;
                    return NONE;
                }
                actionAfterMs = now + 100;
                return LOAD;
            case 1:
                phase = 2;
                sawRotationBusy = false;
                return ROTATE;
            case 3:
                if (Member3PlantStateV1.positionLabel(1).contains("F")) {
                    phase = 4;
                    actionAfterMs = now + 150;
                    return NONE;
                }
                actionAfterMs = now + 100;
                return MARK_FILLED;
            case 4:
                phase = 5;
                sawRotationBusy = false;
                return ROTATE;
            case 6:
                if (Member3PlantStateV1.positionLabel(2).contains("L")) {
                    phase = 7;
                    sawRotationBusy = false;
                    return ROTATE;
                }
                return NONE;
            case 8:
                if (Member3PlantStateV1.positionLabel(3).contains("C")) {
                    phase = 9;
                    actionAfterMs = now + 150;
                    return NONE;
                }
                actionAfterMs = now + 100;
                return MARK_CAPPED;
            case 9:
                phase = 10;
                sawRotationBusy = false;
                return ROTATE;
            case 11:
                if ("empty".equals(Member3PlantStateV1.positionLabel(4))) {
                    phase = 12;
                    complete = true;
                    return PASS;
                }
                actionAfterMs = now + 100;
                return REMOVE;
            default:
                return NONE;
        }
    }

    public static synchronized void onRotationDone() {
        if (sawRotationBusy) {
            advanceAfterRotation(System.currentTimeMillis());
        }
    }

    private static boolean isWaitingForRotation() {
        return phase == 2 || phase == 5 || phase == 7 || phase == 10;
    }

    private static void advanceAfterRotation(long now) {
        if (phase == 2) {
            phase = 3;
        }
        else if (phase == 5) {
            phase = 6;
        }
        else if (phase == 7) {
            phase = 8;
        }
        else if (phase == 10) {
            phase = 11;
        }
        sawRotationBusy = false;
        actionAfterMs = now + 100;
    }

    public static synchronized boolean isComplete() {
        return complete;
    }
}
