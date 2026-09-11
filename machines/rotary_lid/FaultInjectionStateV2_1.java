/** Test-only fault request state shared by the native GUI and SystemJ CDs. */
public final class FaultInjectionStateV2_1 {
    private static BoundedSignalOfferV1 transferOffer =
        new BoundedSignalOfferV1(20, 500L, 100L);
    private static long sequence;
    private static String armedFault;
    private static String transferRequestId;

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
        }
        else {
            throw new IllegalArgumentException("unknown fault: " + faultCode);
        }
        armedFault = faultCode;
        return true;
    }

    public static synchronized String nextTransferRequest() {
        return transferOffer.nextReactionValue();
    }

    public static synchronized void consumed(String faultCode) {
        if (faultCode != null && faultCode.equals(armedFault)) {
            if (transferRequestId != null) {
                transferOffer.acknowledge(transferRequestId);
                transferRequestId = null;
            }
            armedFault = null;
        }
    }

    public static synchronized String armedFault() {
        return armedFault == null ? "-" : armedFault;
    }

    public static synchronized void reset() {
        transferOffer = new BoundedSignalOfferV1(20, 500L, 100L);
        transferRequestId = null;
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
