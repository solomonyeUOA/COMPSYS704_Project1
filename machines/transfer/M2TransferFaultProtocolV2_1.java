/** Parsers for the frozen M2/M3 V2.1 transfer fault exchange. */
public final class M2TransferFaultProtocolV2_1 {
    public static final class FaultEvent {
        public final String raw;
        public final String eventId;
        public final String sourceEpoch;
        public final String faultCode;
        public final String severity;
        public final String bottleId;
        public final long stateVersion;

        private FaultEvent(String payload, String[] fields) {
            raw = payload;
            eventId = required(fields[1], "eventId");
            sourceEpoch = required(fields[2], "sourceEpoch");
            if (!"TRANSFER".equals(fields[3])) {
                throw new IllegalArgumentException("wrong subsystem");
            }
            faultCode = required(fields[4], "faultCode");
            severity = required(fields[5], "severity");
            bottleId = required(fields[6], "bottleId");
            if (!"-".equals(bottleId)) {
                M2BottleContextV1.validateToken(bottleId, "bottleId");
            }
            stateVersion = unsignedLong(fields[7], "stateVersion");
        }
    }

    public static final class RecoveryRequest {
        public final String raw;
        public final String eventId;
        public final String sourceEpoch;
        public final String action;
        public final int attempt;
        public final long expectedStateVersion;

        private RecoveryRequest(String payload, String[] fields) {
            raw = payload;
            eventId = required(fields[1], "eventId");
            sourceEpoch = required(fields[2], "sourceEpoch");
            action = required(fields[3], "action");
            attempt = positiveInt(fields[4], "attempt");
            expectedStateVersion = unsignedLong(
                fields[5],
                "expectedStateVersion"
            );
        }
    }

    private M2TransferFaultProtocolV2_1() {
    }

    public static FaultEvent parseFaultEvent(String payload) {
        return new FaultEvent(payload, fields(payload, 8));
    }

    public static RecoveryRequest parseRecoveryRequest(String payload) {
        return new RecoveryRequest(payload, fields(payload, 6));
    }

    public static void validateRecoveryResult(String payload) {
        String[] fields = fields(payload, 8);
        required(fields[1], "eventId");
        required(fields[2], "sourceEpoch");
        positiveInt(fields[3], "attempt");
        required(fields[4], "outcome");
        required(fields[5], "safeEvidence");
        required(fields[6], "serviceEvidence");
        unsignedLong(fields[7], "resultingStateVersion");
    }

    private static String[] fields(String payload, int expected) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != expected || !"V2".equals(fields[0])) {
            throw new IllegalArgumentException("invalid V2 payload");
        }
        return fields;
    }

    private static String required(String value, String name) {
        if (value == null || value.isEmpty() ||
            !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static long unsignedLong(String value, String name) {
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException(name + " must be unsigned");
        }
        return Long.parseLong(value);
    }

    private static int positiveInt(String value, String name) {
        long valueAsLong = unsignedLong(value, name);
        if (valueAsLong <= 0 || valueAsLong > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return (int) valueAsLong;
    }
}
