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
    private static BoundedStringSignalOfferV1 faultOffer;
    private static BoundedStringSignalOfferV1 resultOffer;
    private static BoundedStringSignalOfferV1 resumeOffer;

    private FaultToleranceDemoStateV2_1() {
    }

    public static synchronized void reset() {
        intentSeen = false;
        readySeen = false;
        passPrinted = false;
        faultOffer = newOffer();
        resultOffer = newOffer();
        resumeOffer = newOffer();
        faultOffer.begin("fault", System.currentTimeMillis());
        M2TransferFaultAdapterStateV2_1.reset();
        FaultSupervisorStateV2_1.reset();
    }

    public static synchronized void onIntent(String payload) {
        if ("V2|IP-DEMO-01|IP-DEMO|RETRY_TRANSFER|1|1".equals(payload)) {
            intentSeen = true;
            faultOffer.discard();
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
            if (!faultOffer.isPending()) {
                faultOffer.begin("fault", now);
            }
            return faultOffer.nextValue(now) == null ? WAIT : SEND_FAULT;
        }
        if (!readySeen && !"RECOVERY_READY".equals(
            FaultSupervisorStateV2_1.stateName())) {
            if (!"WAITING_RESULT".equals(
                FaultSupervisorStateV2_1.stateName())) {
                return WAIT;
            }
            if (!resultOffer.isPending()) {
                resultOffer.begin("result", now);
            }
            return resultOffer.nextValue(now) == null ? WAIT : SEND_RESULT;
        }
        if (!"IDLE".equals(FaultSupervisorStateV2_1.stateName())) {
            resultOffer.discard();
            if (!resumeOffer.isPending()) {
                resumeOffer.begin("resume", now);
            }
            return resumeOffer.nextValue(now) == null ? WAIT : SEND_RESUME;
        }
        resumeOffer.discard();
        if (!passPrinted) {
            passPrinted = true;
            return PASS;
        }
        return WAIT;
    }

    private static BoundedStringSignalOfferV1 newOffer() {
        return new BoundedStringSignalOfferV1(5, 300L, 100L);
    }
}
