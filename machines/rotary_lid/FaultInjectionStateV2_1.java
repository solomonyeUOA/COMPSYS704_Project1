/** Test-only fault request state shared by the native GUI and SystemJ CDs. */
public final class FaultInjectionStateV2_1 {
    private static final int MAXIMUM_TRANSFER_DELIVERY_ROUNDS = 3;
    private static BoundedSignalOfferV1 transferOffer =
        new BoundedSignalOfferV1(20, 500L, 100L);
    private static long sequence;
    private static String armedFault;
    private static String transferRequestId;
    private static int transferDeliveryRounds;
    private static boolean transferRequestAccepted;
    private static String transferDeliveryError;

    private FaultInjectionStateV2_1() {
    }

    public static synchronized boolean arm(String faultCode) {
        if (faultCode == null || armedFault != null) {
            return false;
        }
        if (isRotaryOrLid(faultCode)) {
            if (!Member3MachineStateV1.armTestFault(faultCode)) {
                return false;
            }
        }
        else if (isTransfer(faultCode)) {
            String requestId = "GUI-INJECT-" + (++sequence);
            if (!transferOffer.arm(requestId, requestId + "|" + faultCode)) {
                return false;
            }
            transferRequestId = requestId;
            transferDeliveryRounds = 1;
            transferRequestAccepted = false;
            transferDeliveryError = null;
        }
        else {
            throw new IllegalArgumentException("unknown fault: " + faultCode);
        }
        transferDeliveryError = null;
        armedFault = faultCode;
        return true;
    }

    public static synchronized String nextTransferRequest() {
        if (transferRequestId != null && !transferRequestAccepted &&
            !transferOffer.isActive()) {
            if (transferDeliveryRounds >= MAXIMUM_TRANSFER_DELIVERY_ROUNDS) {
                transferDeliveryError =
                    "M2 did not acknowledge the fault-injection request";
                transferRequestId = null;
                armedFault = null;
                return null;
            }
            transferDeliveryRounds++;
            transferOffer.arm(
                transferRequestId,
                transferRequestId + "|" + armedFault
            );
        }
        return transferOffer.nextReactionValue();
    }

    public static synchronized boolean onTransferAcknowledgement(
        String payload
    ) {
        String[] fields = payload == null ? new String[0] :
            payload.split("\\|", -1);
        if (fields.length != 3 || !"V1".equals(fields[0]) ||
            transferRequestId == null ||
            !transferRequestId.equals(fields[1])) {
            return false;
        }
        if ("ACCEPTED".equals(fields[2])) {
            transferRequestAccepted = true;
            transferOffer.acknowledge(transferRequestId);
            transferDeliveryError = null;
            return true;
        }
        if ("REJECTED".equals(fields[2])) {
            transferOffer.acknowledge(transferRequestId);
            transferDeliveryError = "M2 rejected " + armedFault;
            transferRequestId = null;
            armedFault = null;
            return true;
        }
        return false;
    }

    public static synchronized void consumed(String faultCode) {
        if (faultCode != null && faultCode.equals(armedFault)) {
            if (transferRequestId != null) {
                transferOffer.acknowledge(transferRequestId);
                transferRequestId = null;
            }
            transferRequestAccepted = false;
            transferDeliveryRounds = 0;
            armedFault = null;
        }
    }

    public static synchronized String armedFault() {
        return armedFault == null ? "-" : armedFault;
    }

    public static synchronized String transferDeliveryError() {
        return transferDeliveryError == null ? "-" : transferDeliveryError;
    }

    public static synchronized boolean transferRequestAccepted() {
        return transferRequestAccepted;
    }

    public static synchronized boolean hasTransferRequest() {
        return transferRequestId != null;
    }

    public static synchronized void reset() {
        transferOffer = new BoundedSignalOfferV1(20, 500L, 100L);
        transferRequestId = null;
        transferDeliveryRounds = 0;
        transferRequestAccepted = false;
        transferDeliveryError = null;
        armedFault = null;
    }

    private static boolean isRotaryOrLid(String faultCode) {
        return "ALIGNMENT_TIMEOUT".equals(faultCode) ||
            "MOTOR_STALL".equals(faultCode) ||
            "POSITION_SENSOR_FAILURE".equals(faultCode) ||
            "MAGAZINE_EMPTY".equals(faultCode) ||
            "PICK_TIMEOUT".equals(faultCode) ||
            "PLACEMENT_TIMEOUT".equals(faultCode) ||
            "LID_SENSOR_FAULT".equals(faultCode);
    }

    private static boolean isTransfer(String faultCode) {
        return "ARRIVAL_TIMEOUT".equals(faultCode) ||
            "DEPARTURE_TIMEOUT".equals(faultCode) ||
            "PHOTO_EYE_FAILURE".equals(faultCode) ||
            "POSITION_CONFLICT".equals(faultCode);
    }
}
