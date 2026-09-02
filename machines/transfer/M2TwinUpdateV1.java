/** ASCII adapters for the M2 Digital Twin's versioned update boundary. */
public final class M2TwinUpdateV1 {
    public static final class WorkpieceUpdate {
        public final String raw;
        public final String eventId;
        public final String workpieceId;
        public final String eventType;
        public final String resourceId;
        public final String details;
        public final long eventTimeMillis;

        private WorkpieceUpdate(String payload, String[] fields) {
            raw = payload;
            eventId = token(fields[2], "eventId");
            workpieceId = token(fields[3], "workpieceId");
            eventType = token(fields[4], "eventType");
            resourceId = optionalToken(fields[5], "resourceId");
            details = optionalToken(fields[6], "details");
            eventTimeMillis = unsignedLong(fields[7], "eventTimeMillis");
        }
    }

    public static final class ResourceUpdate {
        public final String raw;
        public final String eventId;
        public final String resourceId;
        public final String resourceType;
        public final String linkedWorkpieceId;
        public final int status;
        public final String operation;
        public final String fault;
        public final long eventTimeMillis;

        private ResourceUpdate(String payload, String[] fields) {
            raw = payload;
            eventId = token(fields[2], "eventId");
            resourceId = token(fields[3], "resourceId");
            resourceType = token(fields[4], "resourceType");
            linkedWorkpieceId = optionalToken(
                fields[5],
                "linkedWorkpieceId"
            );
            status = nonNegativeInt(fields[6], "status");
            if (!M2StatusV1.isValid(status)) {
                throw new IllegalArgumentException("invalid status");
            }
            operation = optionalToken(fields[7], "operation");
            fault = optionalToken(fields[8], "fault");
            eventTimeMillis = unsignedLong(fields[9], "eventTimeMillis");
        }
    }

    private M2TwinUpdateV1() {
    }

    /**
     * V1|W|eventId|workpieceId|eventType|resourceId|details|eventTimeMillis
     */
    public static String workpiece(
        String eventId,
        String workpieceId,
        String eventType,
        String resourceId,
        String details,
        long eventTimeMillis
    ) {
        return "V1|W|" + token(eventId, "eventId") + "|" +
            token(workpieceId, "workpieceId") + "|" +
            token(eventType, "eventType") + "|" +
            optionalToken(resourceId, "resourceId") + "|" +
            optionalToken(details, "details") + "|" + eventTimeMillis;
    }

    /**
     * V1|R|eventId|resourceId|resourceType|linkedWorkpieceId|status|
     * operation|fault|eventTimeMillis
     */
    public static String resource(
        String eventId,
        String resourceId,
        String resourceType,
        String linkedWorkpieceId,
        int status,
        String operation,
        String fault,
        long eventTimeMillis
    ) {
        if (!M2StatusV1.isValid(status)) {
            throw new IllegalArgumentException("invalid status");
        }
        return "V1|R|" + token(eventId, "eventId") + "|" +
            token(resourceId, "resourceId") + "|" +
            token(resourceType, "resourceType") + "|" +
            optionalToken(linkedWorkpieceId, "linkedWorkpieceId") + "|" +
            status + "|" + optionalToken(operation, "operation") + "|" +
            optionalToken(fault, "fault") + "|" + eventTimeMillis;
    }

    public static WorkpieceUpdate parseWorkpiece(String payload) {
        String[] fields = fields(payload, 8, "W");
        return new WorkpieceUpdate(payload, fields);
    }

    public static ResourceUpdate parseResource(String payload) {
        String[] fields = fields(payload, 10, "R");
        return new ResourceUpdate(payload, fields);
    }

    private static String[] fields(String payload, int count, String kind) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != count || !"V1".equals(fields[0]) ||
            !kind.equals(fields[1])) {
            throw new IllegalArgumentException("invalid V1 " + kind +
                " payload");
        }
        return fields;
    }

    private static String token(String value, String name) {
        M2BottleContextV1.validateToken(value, name);
        return value;
    }

    private static String optionalToken(String value, String name) {
        if (value == null || value.isEmpty() || "-".equals(value)) {
            return "-";
        }
        return token(value, name);
    }

    private static long unsignedLong(String value, String name) {
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException(name + " must be unsigned");
        }
        return Long.parseLong(value);
    }

    private static int nonNegativeInt(String value, String name) {
        long parsed = unsignedLong(value, name);
        if (parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is too large");
        }
        return (int) parsed;
    }
}
