import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;

/** DigitalTwinCD owns the store; producers only append immutable observations. */
public final class DigitalTwinStateV1 {
    private static DigitalTwinStoreV1 store = new DigitalTwinStoreV1();
    private static final Map<String, String> workpieceOutbox = new LinkedHashMap<String, String>();
    private static final Map<String, String> resourceOutbox = new LinkedHashMap<String, String>();
    private static final Map<String, String> observedEvents = new LinkedHashMap<String, String>();
    private static final Set<String> retiredEvents = new LinkedHashSet<String>();
    private static String pendingSnapshot;
    private static long snapshotSequence;
    private static long nextPublishMillis;

    private DigitalTwinStateV1() {
    }

    public static synchronized void reset() {
        store = new DigitalTwinStoreV1();
        workpieceOutbox.clear();
        resourceOutbox.clear();
        observedEvents.clear();
        pendingSnapshot = null;
        nextPublishMillis = 0;
    }

    public static synchronized void resetForSystemReset() {
        retiredEvents.addAll(observedEvents.keySet());
        store.resetRuntime();
        workpieceOutbox.clear();
        resourceOutbox.clear();
        observedEvents.clear();
        pendingSnapshot = null;
        nextPublishMillis = 0;
    }

    public static synchronized boolean acceptBatchContext(String payload) {
        return !M2SystemResetStateV1.isQuarantined() && store.acceptBatchContext(payload);
    }

    public static synchronized void enqueueLocalWorkpiece(String payload) {
        acceptWorkpieceUpdate(payload);
    }

    public static synchronized void enqueueLocalResource(String payload) {
        acceptResourceUpdate(payload);
    }

    public static synchronized boolean acceptWorkpieceUpdate(String payload) {
        if (M2SystemResetStateV1.isQuarantined()) { return false; }
        try {
            M2TwinUpdateV1.WorkpieceUpdate update = M2TwinUpdateV1.parseWorkpiece(payload);
            WorkpieceTwin.Stage.valueOf(update.eventType);
            if ("CREATED".equals(update.eventType)) {
                String[] details = update.details.split(",", -1);
                if (details.length != 4) { throw new IllegalArgumentException("invalid context"); }
                new M2BottleContextV1(update.workpieceId, details[0],
                    Integer.parseInt(details[1]), details[2], details[3]);
            }
            if (!M2SystemResetStateV1.allowBottle(update.workpieceId) ||
                retiredEvents.contains(update.eventId)) { return false; }
            String prior = observedEvents.get(update.eventId);
            if (prior != null) { return prior.equals(payload); }
            if (workpieceOutbox.size() >= 4096) { store.recordRejectedUpdate(); return false; }
            observedEvents.put(update.eventId, payload);
            workpieceOutbox.put(update.eventId, payload);
            M2SystemResetStateV1.observeBottle(update.workpieceId);
            // Completion is actual sort/pack completion, not P6 removal.
            if ("SORTED".equals(update.eventType)) {
                acceptWorkpieceUpdate(M2TwinUpdateV1.workpiece(
                    update.eventId + "-COMPLETE", update.workpieceId, "COMPLETE",
                    update.resourceId, "SORT_CONFIRMED", update.eventTimeMillis));
            }
            return true;
        }
        catch (IllegalArgumentException invalid) { store.recordRejectedUpdate(); return false; }
    }

    public static synchronized boolean acceptResourceUpdate(String payload) {
        if (M2SystemResetStateV1.isQuarantined()) { return false; }
        try {
            M2TwinUpdateV1.ResourceUpdate update = M2TwinUpdateV1.parseResource(payload);
            if ((!"-".equals(update.linkedWorkpieceId) &&
                 !M2SystemResetStateV1.allowBottle(update.linkedWorkpieceId)) ||
                retiredEvents.contains(update.eventId)) { return false; }
            String prior = observedEvents.get(update.eventId);
            if (prior != null) { return prior.equals(payload); }
            if (resourceOutbox.size() >= 4096) { store.recordRejectedUpdate(); return false; }
            observedEvents.put(update.eventId, payload);
            resourceOutbox.put(update.eventId, payload);
            M2SystemResetStateV1.observeBottle(update.linkedWorkpieceId);
            return true;
        }
        catch (IllegalArgumentException invalid) { store.recordRejectedUpdate(); return false; }
    }

    /** Upstream evidence describes the last observed completed resource operation. */
    public static synchronized boolean acceptExternalWorkpieceObservation(String payload) {
        if (!acceptWorkpieceUpdate(payload)) { return false; }
        M2TwinUpdateV1.WorkpieceUpdate update = M2TwinUpdateV1.parseWorkpiece(payload);
        String type = "FILLED".equals(update.eventType) ? "FILLER" :
            "LIDDED".equals(update.eventType) ? "LID_LOADER" :
            "CAPPED".equals(update.eventType) ? "CAPPER" :
            "SORTED".equals(update.eventType) ? "SORTPACK" : null;
        if (type != null && !"-".equals(update.resourceId)) {
            acceptResourceUpdate(M2TwinUpdateV1.resource(update.eventId + "-RESOURCE",
                update.resourceId, type, update.workpieceId, M2StatusV1.DONE,
                "OBSERVED_" + update.eventType, "-", update.eventTimeMillis));
        }
        return true;
    }

    /** Called by DigitalTwinCD; retries delayed stages after prerequisites. */
    public static synchronized void drainObservations() {
        if (M2SystemResetStateV1.isQuarantined()) { return; }
        boolean progress;
        do {
            progress = false;
            Iterator<Map.Entry<String, String>> entries = workpieceOutbox.entrySet().iterator();
            while (entries.hasNext()) {
                String payload = entries.next().getValue();
                if (store.isWorkpieceUpdateReady(payload)) {
                    store.applyWorkpieceUpdate(payload);
                    entries.remove();
                    progress = true;
                }
                else if (store.isWorkpieceUpdateObsolete(payload)) {
                    store.recordRejectedUpdate();
                    entries.remove();
                }
            }
        } while (progress && !workpieceOutbox.isEmpty());
        for (String payload : resourceOutbox.values()) { store.applyResourceUpdate(payload); }
        resourceOutbox.clear();
    }

    public static synchronized void requestSnapshot(String request) {
        drainObservations();
        pendingSnapshot = store.snapshot(request);
    }

    public static synchronized String takeSnapshot() {
        String result = pendingSnapshot;
        pendingSnapshot = null;
        return result;
    }

    public static synchronized int getWorkpieceCount() {
        return store.getWorkpieceCount();
    }

    public static synchronized int getResourceCount() {
        return store.getResourceCount();
    }

    /** Complete periodic snapshots tolerate missed UI pulses. */
    public static synchronized String nextVisualisationSnapshot(long nowMillis) {
        if (M2SystemResetStateV1.isQuarantined() || nowMillis < nextPublishMillis) {
            return null;
        }
        drainObservations();
        nextPublishMillis = nowMillis + 250L;
        return store.visualisationSnapshot(M2SystemResetStateV1.getGeneration(), ++snapshotSequence);
    }

    public static synchronized int getPendingObservationCount() { return workpieceOutbox.size(); }
}
