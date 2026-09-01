import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single-owner Digital Twin store. It validates updates, rejects conflicting
 * duplicates and returns immutable snapshots only.
 */
public final class DigitalTwinStoreV1 {
    private final Map<String, WorkpieceTwin> workpieces =
        new LinkedHashMap<String, WorkpieceTwin>();
    private final Map<String, ResourceTwin> resources =
        new LinkedHashMap<String, ResourceTwin>();
    private final Map<String, String> acceptedEvents =
        new LinkedHashMap<String, String>();
    private String batchContext = "-";
    private int rejectedUpdateCount;

    public synchronized boolean acceptBatchContext(String payload) {
        String[] fields = payload == null ? new String[0] :
            payload.split("\\|", -1);
        if (fields.length != 7 || !"V1".equals(fields[0]) ||
            !fields[3].matches("0|[1-9][0-9]*") ||
            !fields[4].matches("0|[1-9][0-9]*") ||
            !fields[5].matches("[1-9][0-9]*")) {
            rejectedUpdateCount++;
            return false;
        }
        int ratioA = Integer.parseInt(fields[3]);
        int ratioB = Integer.parseInt(fields[4]);
        if (ratioA > 100 || ratioB > 100 || ratioA + ratioB != 100) {
            rejectedUpdateCount++;
            return false;
        }
        try {
            M2BottleContextV1.validateToken(fields[1], "orderId");
            M2BottleContextV1.validateToken(fields[2], "productId");
            M2BottleContextV1.validateToken(fields[6], "profileRef");
        }
        catch (IllegalArgumentException exception) {
            rejectedUpdateCount++;
            return false;
        }
        batchContext = payload;
        return true;
    }

    public synchronized boolean applyWorkpieceUpdate(String payload) {
        M2TwinUpdateV1.WorkpieceUpdate update;
        try {
            update = M2TwinUpdateV1.parseWorkpiece(payload);
        }
        catch (IllegalArgumentException exception) {
            rejectedUpdateCount++;
            return false;
        }
        Boolean duplicate = duplicateResult(update.eventId, payload);
        if (duplicate != null) {
            if (!duplicate.booleanValue()) {
                rejectedUpdateCount++;
            }
            return duplicate.booleanValue();
        }
        WorkpieceTwin twin = workpieces.get(update.workpieceId);
        boolean accepted;
        if ("CREATED".equals(update.eventType)) {
            accepted = twin == null && createWorkpiece(update);
        }
        else {
            accepted = twin != null && twin.apply(update);
        }
        if (!accepted) {
            rejectedUpdateCount++;
            return false;
        }
        acceptedEvents.put(update.eventId, payload);
        return true;
    }

    public synchronized boolean applyResourceUpdate(String payload) {
        M2TwinUpdateV1.ResourceUpdate update;
        try {
            update = M2TwinUpdateV1.parseResource(payload);
        }
        catch (IllegalArgumentException exception) {
            rejectedUpdateCount++;
            return false;
        }
        Boolean duplicate = duplicateResult(update.eventId, payload);
        if (duplicate != null) {
            if (!duplicate.booleanValue()) {
                rejectedUpdateCount++;
            }
            return duplicate.booleanValue();
        }
        ResourceTwin twin = resources.get(update.resourceId);
        if (twin == null) {
            twin = new ResourceTwin(update.resourceId, update.resourceType);
            resources.put(update.resourceId, twin);
        }
        if (!twin.apply(update)) {
            rejectedUpdateCount++;
            return false;
        }
        acceptedEvents.put(update.eventId, payload);
        return true;
    }

    public synchronized WorkpieceTwin.Snapshot workpieceSnapshot(String id) {
        WorkpieceTwin twin = workpieces.get(id);
        return twin == null ? null : twin.snapshot();
    }

    public synchronized ResourceTwin.Snapshot resourceSnapshot(String id) {
        ResourceTwin twin = resources.get(id);
        return twin == null ? null : twin.snapshot();
    }

    public synchronized String snapshot(String request) {
        if (request == null || request.isEmpty() || "*".equals(request)) {
            return allSnapshot();
        }
        if (request.startsWith("W:")) {
            WorkpieceTwin.Snapshot value = workpieceSnapshot(
                request.substring(2)
            );
            return value == null ? "V1|NOT_FOUND|" + request :
                value.encode();
        }
        if (request.startsWith("R:")) {
            ResourceTwin.Snapshot value = resourceSnapshot(
                request.substring(2)
            );
            return value == null ? "V1|NOT_FOUND|" + request :
                value.encode();
        }
        WorkpieceTwin.Snapshot workpiece = workpieceSnapshot(request);
        if (workpiece != null) {
            return workpiece.encode();
        }
        ResourceTwin.Snapshot resource = resourceSnapshot(request);
        return resource == null ? "V1|NOT_FOUND|" + request :
            resource.encode();
    }

    public synchronized int getWorkpieceCount() {
        return workpieces.size();
    }

    public synchronized int getResourceCount() {
        return resources.size();
    }

    public synchronized int getRejectedUpdateCount() {
        return rejectedUpdateCount;
    }

    private boolean createWorkpiece(
        M2TwinUpdateV1.WorkpieceUpdate update
    ) {
        String[] details = update.details.split(",", -1);
        if (details.length != 4 ||
            !details[1].matches("0|[1-9][0-9]*")) {
            return false;
        }
        M2BottleContextV1 context;
        try {
            context = new M2BottleContextV1(
                update.workpieceId,
                details[0],
                Integer.parseInt(details[1]),
                details[2],
                details[3]
            );
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
        workpieces.put(
            update.workpieceId,
            new WorkpieceTwin(context, update.eventTimeMillis)
        );
        return true;
    }

    private Boolean duplicateResult(String eventId, String payload) {
        String previous = acceptedEvents.get(eventId);
        if (previous == null) {
            return null;
        }
        return Boolean.valueOf(previous.equals(payload));
    }

    private String allSnapshot() {
        StringBuilder result = new StringBuilder();
        result.append("V1|ALL|W=").append(workpieces.size());
        result.append("|R=").append(resources.size());
        result.append("|REJECTED=").append(rejectedUpdateCount);
        result.append("|BATCH=").append(batchContext.replace('|', ','));
        result.append("|WORKPIECES=");
        boolean first = true;
        for (WorkpieceTwin twin : workpieces.values()) {
            if (!first) {
                result.append(';');
            }
            WorkpieceTwin.Snapshot snapshot = twin.snapshot();
            result.append(snapshot.workpieceId).append(':')
                .append(snapshot.stage.name()).append(':')
                .append(snapshot.version);
            first = false;
        }
        return result.toString();
    }
}
