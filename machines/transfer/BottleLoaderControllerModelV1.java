import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

/** Admission-controlled M2 Bottle Loader state machine. */
public final class BottleLoaderControllerModelV1 {
    private final Queue<M2BottleContextV1> profiles =
        new ArrayDeque<M2BottleContextV1>();
    private final Map<String, String> acceptedProfiles =
        new HashMap<String, String>();
    private int status = M2StatusV1.READY;
    private int requiredQuantity;
    private int completedQuantity;
    private boolean batchActive;
    private M2BottleContextV1 active;
    private String loadedContext;

    /** Status access is observational and never advances the Loader. */
    public int getStatus() {
        return status;
    }

    public int getRequiredQuantity() {
        return requiredQuantity;
    }

    public int getCompletedQuantity() {
        return completedQuantity;
    }

    public int getRemainingQuantity() {
        return Math.max(0, requiredQuantity - completedQuantity);
    }

    public boolean startBatch(int quantity) {
        if (quantity <= 0 || active != null || batchActive ||
            profiles.size() > quantity) {
            return false;
        }
        requiredQuantity = quantity;
        completedQuantity = 0;
        batchActive = true;
        loadedContext = null;
        status = M2StatusV1.READY;
        return true;
    }

    public boolean acceptProfile(String payload) {
        M2BottleContextV1 context;
        try {
            context = M2BottleContextV1.parse(payload);
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
        String encoded = context.encode();
        String previous = acceptedProfiles.get(context.getBottleId());
        if (previous != null) {
            return previous.equals(encoded);
        }
        int reserved = profiles.size() + completedQuantity +
            (active == null ? 0 : 1);
        if (batchActive && reserved >= requiredQuantity) {
            return false;
        }
        acceptedProfiles.put(context.getBottleId(), encoded);
        profiles.add(context);
        return true;
    }

    public boolean hasAcceptedProfile(String bottleId) {
        return acceptedProfiles.containsKey(bottleId);
    }

    /** Returns one bottleId command only when the downstream entry is free. */
    public String takeLoadCommand(boolean conveyorEntryAvailable) {
        if (!batchActive || !conveyorEntryAvailable || active != null ||
            status != M2StatusV1.READY || profiles.isEmpty() ||
            completedQuantity >= requiredQuantity) {
            return null;
        }
        active = profiles.remove();
        status = M2StatusV1.BUSY;
        return active.getBottleId();
    }

    public boolean confirmLoaded(String bottleId) {
        if (active == null || status != M2StatusV1.BUSY ||
            !active.getBottleId().equals(bottleId)) {
            return false;
        }
        completedQuantity++;
        loadedContext = active.encode();
        status = M2StatusV1.DONE;
        return true;
    }

    /**
     * Returns the full unchanged context once for the internal Conveyor
     * hand-off. The next physical admission is re-armed only afterwards.
     */
    public String takeLoadedContext() {
        if (loadedContext == null) {
            return null;
        }
        String result = loadedContext;
        loadedContext = null;
        active = null;
        if (completedQuantity < requiredQuantity) {
            status = M2StatusV1.READY;
        }
        else {
            batchActive = false;
            status = M2StatusV1.DONE;
        }
        return result;
    }

    public boolean isBatchComplete() {
        return !batchActive && requiredQuantity > 0 &&
            completedQuantity == requiredQuantity;
    }
}
