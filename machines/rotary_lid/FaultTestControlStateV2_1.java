/** Legacy signal API retained for compiled peers; operator recovery is disabled. */
public final class FaultTestControlStateV2_1 {
    private FaultTestControlStateV2_1() { }
    public static boolean requestSafeStop() { return false; }
    public static boolean requestResume() { return false; }
    public static boolean requestTransferRecovery() { return false; }
    public static String nextRequest() { return null; }
    public static String nextTransferRequest() { return null; }
    public static void acknowledge() { }
    public static void acknowledgeTransfer() { }
    public static void reset() { }
    public static boolean hasPendingResumeRequest() { return false; }
}
