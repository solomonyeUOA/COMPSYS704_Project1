import java.util.HashSet;
import java.util.Set;

/** Mandatory P6 Labeller with independent verification. */
public final class LabellerControllerModelV1 {
    private final Set<String> completedBottleIds = new HashSet<String>();
    private int status = M2StatusV1.READY;
    private String activeBottleId;
    private boolean labelCommandPending;
    private boolean markLabelledPending;
    private boolean unloadReadyPending;

    /** Status access is observational and never advances labelling. */
    public int getStatus() {
        return status;
    }

    public boolean offerBottle(String bottleId) {
        try {
            M2BottleContextV1.validateToken(bottleId, "bottleId");
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
        if (activeBottleId != null || status != M2StatusV1.READY ||
            completedBottleIds.contains(bottleId)) {
            return false;
        }
        activeBottleId = bottleId;
        labelCommandPending = true;
        status = M2StatusV1.BUSY;
        return true;
    }

    public String takeLabelCommand() {
        if (!labelCommandPending || activeBottleId == null) {
            return null;
        }
        labelCommandPending = false;
        return activeBottleId + "|LABEL_" + activeBottleId;
    }

    /** LABEL_VERIFIED is bottleId|PASS or bottleId|FAIL. */
    public boolean acceptVerification(String payload) {
        String[] fields = payload == null ? new String[0] :
            payload.split("\\|", -1);
        if (fields.length != 2 || activeBottleId == null ||
            !activeBottleId.equals(fields[0]) ||
            status != M2StatusV1.BUSY) {
            return false;
        }
        if ("FAIL".equals(fields[1])) {
            status = M2StatusV1.FAULT;
            return true;
        }
        if (!"PASS".equals(fields[1])) {
            return false;
        }
        status = M2StatusV1.DONE;
        markLabelledPending = true;
        unloadReadyPending = true;
        completedBottleIds.add(activeBottleId);
        return true;
    }

    public String takeMarkLabelled() {
        if (!markLabelledPending) {
            return null;
        }
        markLabelledPending = false;
        return activeBottleId;
    }

    public String takeUnloadReady() {
        if (!unloadReadyPending) {
            return null;
        }
        unloadReadyPending = false;
        String result = activeBottleId;
        if (!markLabelledPending) {
            activeBottleId = null;
            status = M2StatusV1.READY;
        }
        return result;
    }

    public boolean resetFault(boolean labelPathClear,
                              boolean verifierHealthy) {
        if (status != M2StatusV1.FAULT || !labelPathClear ||
            !verifierHealthy) {
            return false;
        }
        activeBottleId = null;
        labelCommandPending = false;
        markLabelledPending = false;
        unloadReadyPending = false;
        status = M2StatusV1.READY;
        return true;
    }
}
