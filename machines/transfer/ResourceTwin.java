import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only Digital Twin state for one production resource. */
public final class ResourceTwin {
    public static final class Snapshot {
        public final String resourceId;
        public final String resourceType;
        public final String linkedWorkpieceId;
        public final int status;
        public final String operation;
        public final String fault;
        public final long version;
        public final long updatedAtMillis;
        public final List<String> history;

        private Snapshot(ResourceTwin twin) {
            resourceId = twin.resourceId;
            resourceType = twin.resourceType;
            linkedWorkpieceId = twin.linkedWorkpieceId;
            status = twin.status;
            operation = twin.operation;
            fault = twin.fault;
            version = twin.version;
            updatedAtMillis = twin.updatedAtMillis;
            history = Collections.unmodifiableList(
                new ArrayList<String>(twin.history)
            );
        }

        public String encode() {
            return "V1|RESOURCE|" + resourceId + "|" + resourceType +
                "|" + linkedWorkpieceId + "|" + status + "|" +
                operation + "|" + fault + "|" + version + "|" +
                updatedAtMillis;
        }
    }

    private final String resourceId;
    private final String resourceType;
    private final List<String> history = new ArrayList<String>();
    private String linkedWorkpieceId = "-";
    private int status = M2StatusV1.IDLE;
    private String operation = "-";
    private String fault = "-";
    private long version;
    private long updatedAtMillis;

    public ResourceTwin(String resourceId, String resourceType) {
        M2BottleContextV1.validateToken(resourceId, "resourceId");
        M2BottleContextV1.validateToken(resourceType, "resourceType");
        this.resourceId = resourceId;
        this.resourceType = resourceType;
    }

    public synchronized boolean apply(M2TwinUpdateV1.ResourceUpdate update) {
        if (update == null || !resourceId.equals(update.resourceId) ||
            !resourceType.equals(update.resourceType) ||
            update.eventTimeMillis < updatedAtMillis) {
            return false;
        }
        linkedWorkpieceId = update.linkedWorkpieceId;
        status = update.status;
        operation = update.operation;
        fault = update.fault;
        updatedAtMillis = update.eventTimeMillis;
        version++;
        history.add(updatedAtMillis + ":" + M2StatusV1.nameOf(status) +
            ":" + operation + ":" + linkedWorkpieceId);
        return true;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(this);
    }
}
