/** Deterministic driver state for the self-contained SystemJ IP demo. */
public final class FaultToleranceDemoStateV2_1 {
    public static final int WAIT = 0;
    public static final int SEND_FAULT = 1;
    public static final int SEND_RESULT = 2;
    public static final int SEND_RESUME = 3;
    public static final int PASS = 4;

    private static boolean intentSeen;
    private static boolean readySeen;
    private static boolean passPrinted;
    private static long nextFaultOfferAt;
    private static long nextResultOfferAt;
    private static long nextResumeOfferAt;

    private FaultToleranceDemoStateV2_1() {
    }

    public static synchronized void reset() {
        intentSeen = false;
        readySeen = false;
        passPrinted = false;
        nextFaultOfferAt = 0;
        nextResultOfferAt = 0;
        nextResumeOfferAt = 0;
        M2TransferFaultAdapterStateV2_1.reset();
        FaultSupervisorStateV2_1.reset();
    }

    public static synchronized void onIntent(String payload) {
        if ("V2|IP-DEMO-01|IP-DEMO|RETRY_TRANSFER|1|1".equals(payload)) {
            intentSeen = true;
        }
    }

    public static synchronized void onReady(String payload) {
        if ("V2|IP-DEMO-01|IP-DEMO|RECOVERY_READY|2".equals(payload)) {
            readySeen = true;
        }
    }

    public static synchronized int nextAction() {
        long now = System.currentTimeMillis();
        if (!intentSeen) {
            if (now >= nextFaultOfferAt) {
                nextFaultOfferAt = now + 250;
                return SEND_FAULT;
            }
            return WAIT;
        }
        if (!readySeen && !"RECOVERY_READY".equals(
            FaultSupervisorStateV2_1.stateName())) {
            if (now >= nextResultOfferAt) {
                nextResultOfferAt = now + 250;
                return SEND_RESULT;
            }
            return WAIT;
        }
        if (!"IDLE".equals(FaultSupervisorStateV2_1.stateName())) {
            if (now >= nextResumeOfferAt) {
                nextResumeOfferAt = now + 250;
                return SEND_RESUME;
            }
            return WAIT;
        }
        if (!passPrinted) {
            passPrinted = true;
            return PASS;
        }
        return WAIT;
    }
}
