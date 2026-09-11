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

    public static synchronized boolean armTestFault(String payload) {
        if (M2SystemResetStateV1.isQuarantined()) {
            return false;
        }
        return M2MachineStateV1.armTransferTestFault(payload);
    }

    public static synchronized boolean recoverTestFault(String payload) {
        String[] fields = payload == null ? new String[0] :
            payload.split("\\|", -1);
        if (fields.length != 6 || !"V2".equals(fields[0]) ||
            !"MANUAL_RECOVER".equals(fields[4]) ||
            !currentEpoch(payload) || !fields[5].matches("0|[1-9][0-9]*")) {
            return false;
        }
        long expectedVersion = Long.parseLong(fields[5]);
        if (!model.matchesActive(
            fields[1], fields[2], fields[3], expectedVersion
        )) {
            return false;
        }
        long resultingVersion = M2MachineStateV1.recoverTransferTestFault(
            fields[3], expectedVersion
        );
        if (resultingVersion < 0L) {
            return false;
        }
        return model.onLocalRecoveryEvidence(
            "V2|" + fields[1] + "|" + fields[2] +
            "|1|SUCCESS|motor_off+occupancy_consistent|" +
            "location_confirmed|" + resultingVersion
        );
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
