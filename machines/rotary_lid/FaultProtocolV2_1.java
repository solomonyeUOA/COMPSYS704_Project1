/** Parsers for the frozen M2/M3 transfer fault-tolerance payloads. */
public final class FaultProtocolV2_1 {
    private FaultProtocolV2_1() {
    }

    public static final class FaultEvent {
        public final String raw;
        public final String eventId;
        public final String sourceEpoch;
        public final String subsystem;
        public final String faultCode;
        public final String severity;
        public final String bottleId;
        public final long stateVersion;

        private FaultEvent(String[] fields, String payload) {
            raw = payload;
            eventId = required(fields[1], "eventId");
            sourceEpoch = required(fields[2], "sourceEpoch");
            subsystem = required(fields[3], "subsystem");
            faultCode = required(fields[4], "faultCode");
            severity = required(fields[5], "severity");
            bottleId = required(fields[6], "bottleId");
            if (!"-".equals(bottleId)) {
                BottleContextV1.validateBottleId(bottleId);
            }
            stateVersion = unsignedLong(fields[7], "stateVersion");
        }
    }

    public static final class RecoveryAck {
        public final String eventId;
        public final String sourceEpoch;
        public final int attempt;
        public final String ack;
        public final String reason;
        public final long acceptedStateVersion;

        private RecoveryAck(String[] fields) {
            eventId = required(fields[1], "eventId");
            sourceEpoch = required(fields[2], "sourceEpoch");
            attempt = positiveInt(fields[3], "attempt");
            ack = required(fields[4], "ack");
            reason = required(fields[5], "reason");
            acceptedStateVersion = unsignedLong(
                fields[6],
                "acceptedStateVersion"
            );
        }
    }

    public static final class RecoveryResult {
        public final String eventId;
        public final String sourceEpoch;
        public final int attempt;
        public final String outcome;
        public final String safeEvidence;
        public final String serviceEvidence;
        public final long resultingStateVersion;

        private RecoveryResult(String[] fields) {
            eventId = required(fields[1], "eventId");
            sourceEpoch = required(fields[2], "sourceEpoch");
            attempt = positiveInt(fields[3], "attempt");
            outcome = required(fields[4], "outcome");
            safeEvidence = required(fields[5], "safeEvidence");
            serviceEvidence = required(fields[6], "serviceEvidence");
            resultingStateVersion = unsignedLong(
                fields[7],
                "resultingStateVersion"
            );
        }
    }

    public static FaultEvent parseFaultEvent(String payload) {
        return new FaultEvent(fields(payload, 8), payload);
    }

    public static RecoveryAck parseRecoveryAck(String payload) {
        return new RecoveryAck(fields(payload, 7));
    }

    public static RecoveryResult parseRecoveryResult(String payload) {
        return new RecoveryResult(fields(payload, 8));
    }

    public static String recoveryRequest(
        FaultEvent event,
        String action,
        int attempt
    ) {
        return "V2|" + event.eventId + "|" + event.sourceEpoch + "|" +
            action + "|" + attempt + "|" + event.stateVersion;
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
        long parsed = unsignedLong(value, name);
        if (parsed <= 0 || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return (int) parsed;
    }
}
