/** Bounded test-only requests from the M3 GUI to the real M1 coordinator. */
public final class FaultTestControlStateV2_1 {
    private static final BoundedStringSignalOfferV1 M1_OFFER =
        new BoundedStringSignalOfferV1(3, 500L, 100L);
    private static final BoundedStringSignalOfferV1 TRANSFER_OFFER =
        new BoundedStringSignalOfferV1(3, 500L, 100L);

    private FaultTestControlStateV2_1() {
    }

    public static synchronized boolean requestSafeStop() {
        return begin("SAFE_STOP");
    }

    public static synchronized boolean requestResume() {
        return begin("RESUME");
    }

    public static synchronized boolean requestTransferRecovery() {
        if (!"TRANSFER".equals(FaultSupervisorStateV2_1.activeSubsystem())) {
            return false;
        }
        String eventId = FaultSupervisorStateV2_1.activeEventId();
        String epoch = FaultSupervisorStateV2_1.activeEpoch();
        String fault = FaultSupervisorStateV2_1.activeFaultCode();
        long version = FaultSupervisorStateV2_1.activeStateVersion();
        if ("-".equals(eventId) || "-".equals(epoch) || version < 0L) {
            return false;
        }
        return TRANSFER_OFFER.begin(
            "V2|" + eventId + "|" + epoch + "|" + fault +
            "|MANUAL_RECOVER|" + version,
            System.currentTimeMillis()
        );
    }

    public static synchronized String nextRequest() {
        return M1_OFFER.nextValue(System.currentTimeMillis());
    }

    public static synchronized String nextTransferRequest() {
        return TRANSFER_OFFER.nextValue(System.currentTimeMillis());
    }

    public static synchronized void acknowledge() {
        M1_OFFER.discard();
    }

    public static synchronized void acknowledgeTransfer() {
        TRANSFER_OFFER.discard();
    }

    public static synchronized void reset() {
        M1_OFFER.discard();
        TRANSFER_OFFER.discard();
    }

    private static boolean begin(String action) {
        String eventId = FaultSupervisorStateV2_1.activeEventId();
        String epoch = FaultSupervisorStateV2_1.activeEpoch();
        long version = "RESUME".equals(action) ?
            FaultSupervisorStateV2_1.latestStateVersion() :
            FaultSupervisorStateV2_1.activeStateVersion();
        if ("-".equals(eventId) || "-".equals(epoch) || version < 0L) {
            return false;
        }
        return M1_OFFER.begin(
            "V2|" + eventId + "|" + epoch + "|" + action + "|" + version,
            System.currentTimeMillis()
        );
    }
}
