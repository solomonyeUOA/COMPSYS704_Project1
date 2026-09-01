import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Digital representation of one physical bottle/product instance.
 * BottleTwin is a conceptual alias, not a second stored object.
 */
public final class WorkpieceTwin {
    public enum Stage {
        CREATED,
        LOADED,
        P1,
        FILLED,
        LIDDED,
        CAPPED,
        P6,
        LABELLED,
        UNLOADED,
        SORTED,
        COMPLETE,
        FAULT
    }

    public static final class Snapshot {
        public final String workpieceId;
        public final String bottleContext;
        public final Stage stage;
        public final String resourceId;
        public final long version;
        public final long updatedAtMillis;
        public final List<String> history;

        private Snapshot(WorkpieceTwin twin) {
            workpieceId = twin.context.getBottleId();
            bottleContext = twin.context.encode();
            stage = twin.stage;
            resourceId = twin.resourceId;
            version = twin.version;
            updatedAtMillis = twin.updatedAtMillis;
            history = Collections.unmodifiableList(
                new ArrayList<String>(twin.history)
            );
        }

        public String encode() {
            return "V1|WORKPIECE|" + workpieceId + "|" + stage.name() +
                "|" + version + "|" + resourceId + "|" +
                updatedAtMillis;
        }
    }

    private final M2BottleContextV1 context;
    private final List<String> history = new ArrayList<String>();
    private Stage stage = Stage.CREATED;
    private String resourceId = "-";
    private long version = 1;
    private long updatedAtMillis;

    public WorkpieceTwin(M2BottleContextV1 context, long createdAtMillis) {
        if (context == null || createdAtMillis < 0) {
            throw new IllegalArgumentException("valid context/time required");
        }
        this.context = context;
        updatedAtMillis = createdAtMillis;
        history.add(createdAtMillis + ":CREATED");
    }

    public synchronized boolean apply(
        M2TwinUpdateV1.WorkpieceUpdate update
    ) {
        if (update == null ||
            !context.getBottleId().equals(update.workpieceId) ||
            update.eventTimeMillis < updatedAtMillis) {
            return false;
        }
        Stage next;
        try {
            next = Stage.valueOf(update.eventType);
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
        if (!legalTransition(stage, next)) {
            return false;
        }
        stage = next;
        resourceId = update.resourceId;
        updatedAtMillis = update.eventTimeMillis;
        version++;
        history.add(updatedAtMillis + ":" + next.name() + "@" +
            resourceId);
        return true;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(this);
    }

    private static boolean legalTransition(Stage current, Stage next) {
        if (next == Stage.FAULT && current != Stage.COMPLETE) {
            return true;
        }
        switch (current) {
            case CREATED:
                return next == Stage.LOADED;
            case LOADED:
                return next == Stage.P1;
            case P1:
                return next == Stage.FILLED;
            case FILLED:
                return next == Stage.LIDDED;
            case LIDDED:
                return next == Stage.CAPPED;
            case CAPPED:
                return next == Stage.P6;
            case P6:
                return next == Stage.LABELLED;
            case LABELLED:
                return next == Stage.UNLOADED;
            case UNLOADED:
                return next == Stage.SORTED || next == Stage.COMPLETE;
            case SORTED:
                return next == Stage.COMPLETE;
            default:
                return false;
        }
    }
}
