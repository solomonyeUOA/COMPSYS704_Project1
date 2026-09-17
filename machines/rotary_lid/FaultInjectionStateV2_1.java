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
    private static boolean transferCancellationPending;
    private static String transferDeliveryError;

    private FaultInjectionStateV2_1() {
    }

    public static synchronized boolean arm(String faultCode) {
        if (faultCode == null || armedFault != null) {
            return false;
        }
        if (isController(faultCode)) {
            if (!Member3MachineStateV1.armControllerFailure(faultCode)) return false;
        }
        else if (isRedundantDevice(faultCode)) {
            if (!Member3PlantStateV1.armRedundantFault(faultCode)) return false;
        }
        else if (isRotaryOrLid(faultCode)) {
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
            transferCancellationPending = false;
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
                if (!transferCancellationPending) {
                    transferDeliveryError =
                        "M2 did not acknowledge the fault-injection request; cancelling it safely";
                    beginTransferCancellation();
                    return transferOffer.nextReactionValue();
                }
                transferDeliveryError =
                    "M2 did not acknowledge cancellation; retry cancellation or reset the disconnected test runtime";
                transferCancellationPending = false;
                transferRequestAccepted = true;
                transferDeliveryRounds = 0;
                return null;
            }
            transferDeliveryRounds++;
            transferOffer.arm(
                transferRequestId,
                transferRequestId + "|" +
                    (transferCancellationPending ? "CANCEL" : armedFault)
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
            if (transferCancellationPending) {
                clearTransferState();
                armedFault = null;
                transferDeliveryError = null;
                return true;
            }
            transferRequestAccepted = true;
            transferOffer.acknowledge(transferRequestId);
            transferDeliveryError = null;
            return true;
        }
        if ("REJECTED".equals(fields[2])) {
            transferOffer.acknowledge(transferRequestId);
            transferDeliveryError = transferCancellationPending ?
                "M2 could not cancel the injection because it had already triggered" :
                "M2 rejected " + armedFault;
            transferRequestId = null;
            if (!transferCancellationPending) {
                armedFault = null;
            }
            transferCancellationPending = false;
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
            clearTransferState();
            armedFault = null;
        }
    }

    public static synchronized boolean cancel() {
        if (armedFault == null) {
            return false;
        }
        if (isController(armedFault)) {
            if (!Member3MachineStateV1.cancelControllerFailure(armedFault)) return false;
            armedFault = null;
            return true;
        }
        if (isRedundantDevice(armedFault)) {
            if (!Member3PlantStateV1.cancelRedundantFault(armedFault)) return false;
            armedFault = null;
            return true;
        }
        if (isRotaryOrLid(armedFault)) {
            if (!Member3MachineStateV1.cancelArmedTestFault(armedFault)) {
                return false;
            }
            armedFault = null;
            transferDeliveryError = null;
            return true;
        }
        if (!isTransfer(armedFault) || transferRequestId == null ||
            transferCancellationPending) {
            return false;
        }
        beginTransferCancellation();
        transferDeliveryError = null;
        return true;
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

    public static synchronized boolean cancellationPending() {
        return transferCancellationPending;
    }

    public static synchronized boolean hasTransferRequest() {
        return transferRequestId != null;
    }

    public static synchronized void reset() {
        Member3MachineStateV1.clearPendingTestFaults();
        Member3PlantStateV1.clearPendingRedundantFault();
        transferOffer = new BoundedSignalOfferV1(20, 500L, 100L);
        transferRequestId = null;
        transferDeliveryRounds = 0;
        transferRequestAccepted = false;
        transferCancellationPending = false;
        transferDeliveryError = null;
        armedFault = null;
    }

    private static void clearTransferState() {
        if (transferRequestId != null) {
            transferOffer.acknowledge(transferRequestId);
        }
        transferRequestId = null;
        transferRequestAccepted = false;
        transferCancellationPending = false;
        transferDeliveryRounds = 0;
    }

    private static void beginTransferCancellation() {
        transferOffer.acknowledge(transferRequestId);
        transferOffer = new BoundedSignalOfferV1(20, 500L, 100L);
        transferOffer.arm(transferRequestId, transferRequestId + "|CANCEL");
        transferCancellationPending = true;
        transferRequestAccepted = false;
        transferDeliveryRounds = 1;
    }

    static boolean isRedundantDevice(String faultCode) {
        return isPhysicalFault(faultCode) || "ROTARY_DRIVE_FAILURE".equals(faultCode) ||
            "PICK_DRIVE_FAILURE".equals(faultCode) ||
            "PLACE_DRIVE_FAILURE".equals(faultCode) ||
            "ROTARY_FEEDBACK_FAILURE".equals(faultCode);
    }

    static boolean isPhysicalFault(String code) {
        if ("ROTARY_FEEDBACK_DISAGREEMENT".equals(code) || "PLACE_LOAD_LOSS".equals(code)) return true;
        if (code == null) return false;
        for (String axis : new String[] {"ROTARY", "PICK", "PLACE"})
            for (String fault : new String[] {"STOP_FAILURE", "HOLD_FAILURE", "ISOLATION_FAILURE",
                    "ENGAGEMENT_FAILURE", "BACKUP_FAILURE", "MECHANICAL_JAM"})
                if ((axis + "_" + fault).equals(code)) return true;
        return false;
    }

    private static boolean isController(String code) {
        return "ROTARY_CONTROLLER_FAILURE".equals(code) || "LID_CONTROLLER_FAILURE".equals(code);
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
