import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * P6 Bottle Unloader. Completion requires matching label permission, matching
 * profile, physical-removal evidence and an empty-P6 sensor.
 */
public final class BottleUnloaderControllerModelV1 {
    private enum DoneWindow {
        READY,
        PRESENT,
        GAP
    }

    private final Map<String, M2BottleContextV1> profiles =
        new LinkedHashMap<String, M2BottleContextV1>();
    private final Set<String> readyBottleIds =
        new LinkedHashSet<String>();
    private final Set<String> completedBottleIds =
        new LinkedHashSet<String>();
    private final long bottleDoneHoldMillis;
    private int status = M2StatusV1.READY;
    private M2BottleContextV1 active;
    private boolean p6ClearPending;
    private boolean sortContextPending;
    private DoneWindow doneWindow = DoneWindow.READY;
    private long doneWindowUntilMillis;

    public BottleUnloaderControllerModelV1() {
        this(500L);
    }

    public BottleUnloaderControllerModelV1(long bottleDoneHoldMillis) {
        if (bottleDoneHoldMillis <= 0) {
            throw new IllegalArgumentException(
                "BOTTLE_DONE hold window must be positive"
            );
        }
        this.bottleDoneHoldMillis = bottleDoneHoldMillis;
    }

    /** Status access is observational and never advances unloading. */
    public int getStatus() {
        return status;
    }

    public boolean acceptProfile(String payload) {
        M2BottleContextV1 context;
        try {
            context = M2BottleContextV1.parse(payload);
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
        M2BottleContextV1 previous = profiles.get(context.getBottleId());
        if (previous != null) {
            return previous.encode().equals(context.encode());
        }
        if (completedBottleIds.contains(context.getBottleId())) {
            return false;
        }
        profiles.put(context.getBottleId(), context);
        return true;
    }

    public boolean acceptUnloadReady(String bottleId) {
        try {
            M2BottleContextV1.validateToken(bottleId, "bottleId");
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
        if (completedBottleIds.contains(bottleId) ||
            (active != null && active.getBottleId().equals(bottleId))) {
            return false;
        }
        return readyBottleIds.add(bottleId);
    }

    /** Starts only when the same bottle has both profile and label evidence. */
    public String takeUnloadCommand() {
        if (active != null || status != M2StatusV1.READY ||
            doneWindow != DoneWindow.READY) {
            return null;
        }
        Iterator<String> iterator = readyBottleIds.iterator();
        while (iterator.hasNext()) {
            String bottleId = iterator.next();
            M2BottleContextV1 context = profiles.get(bottleId);
            if (context == null) {
                continue;
            }
            iterator.remove();
            active = context;
            status = M2StatusV1.BUSY;
            return bottleId;
        }
        return null;
    }

    /** REMOVAL_CONFIRMED is bottleId|true and true means P6 is empty. */
    public boolean acceptRemovalConfirmed(String payload, long nowMillis) {
        String[] fields = payload == null ? new String[0] :
            payload.split("\\|", -1);
        if (fields.length != 2 || active == null ||
            !active.getBottleId().equals(fields[0]) ||
            !"true".equals(fields[1]) || status != M2StatusV1.BUSY) {
            return false;
        }
        status = M2StatusV1.DONE;
        p6ClearPending = true;
        sortContextPending = true;
        doneWindow = DoneWindow.PRESENT;
        doneWindowUntilMillis = nowMillis + bottleDoneHoldMillis;
        return true;
    }

    public String takeP6Clear() {
        if (!p6ClearPending || active == null) {
            return null;
        }
        p6ClearPending = false;
        String result = active.getBottleId();
        finishCycleIfPossible();
        return result;
    }

    public String takeSortContext() {
        if (!sortContextPending || active == null) {
            return null;
        }
        sortContextPending = false;
        String result = active.encode();
        finishCycleIfPossible();
        return result;
    }

    /**
     * Returns true throughout one bounded PRESENT window. It then guarantees
     * at least one false reaction (ABSENT gap) before another bottle can arm.
     */
    public boolean isBottleDonePresent(long nowMillis) {
        if (doneWindow == DoneWindow.PRESENT) {
            if (nowMillis <= doneWindowUntilMillis) {
                return true;
            }
            doneWindow = DoneWindow.GAP;
            return false;
        }
        if (doneWindow == DoneWindow.GAP) {
            doneWindow = DoneWindow.READY;
            finishCycleIfPossible();
        }
        return false;
    }

    public boolean isBottleDoneArmed() {
        return doneWindow == DoneWindow.PRESENT;
    }

    private void finishCycleIfPossible() {
        if (active == null || p6ClearPending || sortContextPending ||
            doneWindow != DoneWindow.READY) {
            return;
        }
        String bottleId = active.getBottleId();
        completedBottleIds.add(bottleId);
        profiles.remove(bottleId);
        active = null;
        status = M2StatusV1.READY;
    }
}
