/** Static SystemJ-facing facade for M2TransferFaultAdapterCD. */
public final class M2TransferFaultAdapterStateV2_1 {
    private static M2TransferFaultAdapterModelV2_1 model =
        new M2TransferFaultAdapterModelV2_1();

    private M2TransferFaultAdapterStateV2_1() {
    }

    public static synchronized void reset() {
        model = new M2TransferFaultAdapterModelV2_1();
    }

    public static synchronized boolean onLocalFault(String payload) {
        return model.onLocalFault(payload);
    }

    public static synchronized boolean onRecoveryRequest(String payload) {
        return model.onRecoveryRequest(payload);
    }

    public static synchronized boolean onLocalRecoveryEvidence(
        String payload
    ) {
        return model.onLocalRecoveryEvidence(payload);
    }

    public static synchronized String takeFaultEvent() {
        return model.takeFaultEvent();
    }

    public static synchronized String takeAck() {
        return model.takeAck();
    }

    public static synchronized String takeIntent() {
        return model.takeIntent();
    }

    public static synchronized String takeResult() {
        return model.takeResult();
    }
}
