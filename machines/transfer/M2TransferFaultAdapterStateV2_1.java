/** Static SystemJ-facing facade for M2TransferFaultAdapterCD. */
public final class M2TransferFaultAdapterStateV2_1 {
    private static M2TransferFaultAdapterModelV2_1 model =
        new M2TransferFaultAdapterModelV2_1();

    private M2TransferFaultAdapterStateV2_1() {
    }

    public static synchronized void reset() {
        model = new M2TransferFaultAdapterModelV2_1();
    }

    public static synchronized void resetForSystemReset() { reset(); }

    private static boolean currentEpoch(String payload) {
        String[] fields = payload == null ? new String[0] : payload.split("\\|", -1);
        return !M2SystemResetStateV1.isQuarantined() && fields.length >= 3 &&
            M2MachineStateV1.getSourceEpoch().equals(fields[2]);
    }

    public static synchronized boolean onLocalFault(String payload) {
        if (!currentEpoch(payload)) { return false; }
        return model.onLocalFault(payload);
    }

    public static synchronized boolean onRecoveryRequest(String payload) {
        if (!currentEpoch(payload)) { return false; }
        return model.onRecoveryRequest(payload);
    }

    public static synchronized boolean onLocalRecoveryEvidence(
        String payload
    ) {
        if (!currentEpoch(payload)) { return false; }
        return model.onLocalRecoveryEvidence(payload);
    }

    public static synchronized String takeFaultEvent() {
        if (M2SystemResetStateV1.isQuarantined()) { return null; }
        return model.takeFaultEvent();
    }

    public static synchronized String takeAck() {
        if (M2SystemResetStateV1.isQuarantined()) { return null; }
        return model.takeAck();
    }

    public static synchronized String takeIntent() {
        if (M2SystemResetStateV1.isQuarantined()) { return null; }
        return model.takeIntent();
    }

    public static synchronized String takeResult() {
        if (M2SystemResetStateV1.isQuarantined()) { return null; }
        return model.takeResult();
    }
}
