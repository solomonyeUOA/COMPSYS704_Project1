import java.util.Locale;
import java.util.Properties;

/** Batch-driven environment stimulus for the simulation-only M4 CD. */
public final class RecognitionSimulatorStateV1 {
    public enum BatchStartResult {
        ACCEPTED,
        DUPLICATE,
        CONFLICT,
        ACTIVE_BATCH,
        INVALID
    }

    private static RecognitionSimulatorStateV1 runtime;
    private static String lastLoggedBottle;
    private static boolean terminalLogged;

    private final String legacySizeCode;
    private final long intervalMillis;
    private final long timeoutMillis;
    private final long requestGapMillis;
    private String activeBatchId;
    private String activeSizeCode;
    private int quantity;
    private int distributed;
    private boolean batchActive;
    private boolean requestActive;
    private boolean legacyIdentifiers;
    private String legacyBottleIdPrefix;
    private long nextBottleMillis;
    private long bottleStartedMillis;
    private long nextRequestMillis;
    private String failure;

    /**
     * A legacy standalone run fixes one environmental size through
     * m4.sim.size. The integrated receiver takes the size from every M1
     * batch request instead, so it never reads that property.
     */
    private RecognitionSimulatorStateV1(
        Properties properties,
        boolean legacyMode
    ) {
        if (legacyMode) {
            legacySizeCode = properties.getProperty("m4.sim.size", "S");
            if (!M4BottleContextV1.SMALL.equals(legacySizeCode) &&
                !M4BottleContextV1.LARGE.equals(legacySizeCode)) {
                throw new IllegalArgumentException(
                    "m4.sim.size must be S or L"
                );
            }
        }
        else {
            legacySizeCode = null;
        }
        intervalMillis = millis(
            properties, "m4.sim.intervalMillis", 1000L, 0L
        );
        timeoutMillis = millis(
            properties, "m4.sim.timeoutMillis", 10000L, 1L
        );
        requestGapMillis = millis(
            properties, "m4.sim.requestGapMillis", 100L, 1L
        );
        if (requestGapMillis >= timeoutMillis) {
            throw new IllegalArgumentException(
                "m4.sim.requestGapMillis must be less than " +
                "m4.sim.timeoutMillis"
            );
        }
    }

    /** Legacy standalone/demo mode retained for backwards compatibility. */
    public static RecognitionSimulatorStateV1 fromProperties(
        Properties properties,
        long nowMillis
    ) {
        RecognitionSimulatorStateV1 result =
            new RecognitionSimulatorStateV1(properties, true);
        int configuredQuantity = M4ProtocolV1.unsignedInteger(
            properties.getProperty("m4.sim.quantity", "1"),
            "m4.sim.quantity"
        );
        if (configuredQuantity == 0) {
            throw new IllegalArgumentException(
                "m4.sim.quantity must be positive"
            );
        }
        String prefix = properties.getProperty(
            "m4.sim.bottleIdPrefix", "SIM-B"
        );
        M4ProtocolV1.validateBottleId(prefix);
        long delay = millis(
            properties, "m4.sim.startDelayMillis", 10000L, 0L
        );
        result.startLegacyBatch(
            prefix,
            configuredQuantity,
            nowMillis + delay
        );
        return result;
    }

    /**
     * Preferred integrated mode: starts idle, ignores m4.sim.quantity, and
     * ignores m4.sim.size because every batch carries its own size code.
     */
    public static RecognitionSimulatorStateV1 batchDrivenFromProperties(
        Properties properties,
        long nowMillis
    ) {
        return new RecognitionSimulatorStateV1(properties, false);
    }

    public BatchStartResult startBatch(String batchId, int requestedQuantity) {
        return startBatch(
            batchId,
            requestedQuantity,
            System.currentTimeMillis()
        );
    }

    /** Size-less entry point kept for callers that use the default size. */
    public BatchStartResult startBatch(
        String batchId,
        int requestedQuantity,
        long nowMillis
    ) {
        return startBatch(
            batchId,
            requestedQuantity,
            defaultSizeCode(),
            nowMillis
        );
    }

    public BatchStartResult startBatch(
        String batchId,
        int requestedQuantity,
        String requestedSizeCode,
        long nowMillis
    ) {
        try {
            M4ProtocolV1.validateBottleId(batchId);
        }
        catch (IllegalArgumentException invalid) {
            return BatchStartResult.INVALID;
        }
        if (requestedQuantity < 1 || !isSupportedSize(requestedSizeCode)) {
            return BatchStartResult.INVALID;
        }

        // One batch identity owns one quantity and one size. Retrying the
        // same contract is idempotent; changing either field under the same
        // ID is a protocol conflict, never a silent re-parameterisation.
        if (batchId.equals(activeBatchId)) {
            return requestedQuantity == quantity &&
                requestedSizeCode.equals(activeSizeCode) ?
                BatchStartResult.DUPLICATE : BatchStartResult.CONFLICT;
        }
        // Only a batch that is still running may block the next one. A
        // terminated batch - finished or failed - must never wedge the
        // simulator for the rest of the run.
        if (batchActive) {
            return BatchStartResult.ACTIVE_BATCH;
        }

        activeBatchId = batchId;
        activeSizeCode = requestedSizeCode;
        quantity = requestedQuantity;
        distributed = 0;
        batchActive = true;
        requestActive = false;
        legacyIdentifiers = false;
        legacyBottleIdPrefix = null;
        nextBottleMillis = nowMillis;
        bottleStartedMillis = 0L;
        nextRequestMillis = nowMillis;
        failure = null;
        return BatchStartResult.ACCEPTED;
    }

    /** Accepts batchId|quantity|sizeCode, M1's canonical batch request. */
    public BatchStartResult startBatchPayload(
        String payload,
        long nowMillis
    ) {
        String[] fields = requestFields(payload);
        if (fields == null) {
            return BatchStartResult.INVALID;
        }
        try {
            int requestedQuantity = M4ProtocolV1.unsignedInteger(
                fields[1], "quantity"
            );
            String requestedSizeCode = fields.length == 3 ?
                fields[2] : defaultSizeCode();
            return startBatch(
                fields[0],
                requestedQuantity,
                requestedSizeCode,
                nowMillis
            );
        }
        catch (IllegalArgumentException invalid) {
            return BatchStartResult.INVALID;
        }
    }

    /** No catch-up bursts: at most one recognition request per call. */
    public String tick(long nowMillis, boolean contextDistributed) {
        if (!batchActive || failure != null ||
            nowMillis < nextBottleMillis) {
            return null;
        }
        if (!requestActive) {
            requestActive = true;
            bottleStartedMillis = nowMillis;
            nextRequestMillis = nowMillis;
        }
        if (contextDistributed) {
            distributed++;
            requestActive = false;
            if (distributed == quantity) {
                batchActive = false;
            }
            else {
                nextBottleMillis = nowMillis + intervalMillis;
            }
            return null;
        }
        if (nowMillis - bottleStartedMillis >= timeoutMillis) {
            // Record the identity before the batch is released, because
            // currentBottleId() is only defined while a batch is active.
            failure = "context distribution timed out for " +
                currentBottleId();
            batchActive = false;
            requestActive = false;
            return null;
        }
        if (nowMillis < nextRequestMillis) {
            return null;
        }
        nextRequestMillis = nowMillis + requestGapMillis;
        return currentBottleId() + "|" + activeSizeCode;
    }

    public String currentBottleId() {
        if (!batchActive) {
            return null;
        }
        String suffix = String.format(
            Locale.ROOT,
            "%03d",
            Integer.valueOf(distributed + 1)
        );
        return legacyIdentifiers ?
            legacyBottleIdPrefix + suffix : activeBatchId + "-B" + suffix;
    }

    public boolean isFinished() {
        return activeBatchId != null && !batchActive && failure == null &&
            distributed == quantity;
    }

    public boolean isBatchActive() {
        return batchActive;
    }

    public String activeBatchId() {
        return activeBatchId;
    }

    public int batchQuantity() {
        return quantity;
    }

    /** Size code of the current batch, null before the first one. */
    public String batchSizeCode() {
        return activeSizeCode;
    }

    public int distributedCount() {
        return distributed;
    }

    public String failureReason() {
        return failure;
    }

    /** Starts the old property-configured finite batch. */
    public static synchronized void start() {
        runtime = fromProperties(
            System.getProperties(), System.currentTimeMillis()
        );
        resetRuntimeLogging();
        System.out.println(
            "[M4-SIM] standalone legacy mode quantity=" +
            runtime.quantity + " size=" + runtime.activeSizeCode +
            " prefix=" + runtime.legacyBottleIdPrefix
        );
    }

    /** Starts the preferred integrated receiver in IDLE. */
    public static synchronized void startBatchDriven() {
        runtime = batchDrivenFromProperties(
            System.getProperties(), System.currentTimeMillis()
        );
        resetRuntimeLogging();
        System.out.println(
            "[M4-SIM] batch-driven mode IDLE; awaiting " +
            "M4_SIM_BATCH_REQUEST batchId|quantity|sizeCode " +
            "(m4.sim.quantity and m4.sim.size ignored)"
        );
    }

    public static synchronized boolean acceptBatchRequest(String payload) {
        BatchStartResult result = runtime.startBatchPayload(
            payload,
            System.currentTimeMillis()
        );
        if (result == BatchStartResult.ACCEPTED) {
            lastLoggedBottle = null;
            terminalLogged = false;
            System.out.println(
                "[M4-SIM] batch accepted id=" + runtime.activeBatchId +
                " quantity=" + runtime.quantity +
                " size=" + runtime.activeSizeCode + legacyNote(payload)
            );
            return true;
        }
        if (result == BatchStartResult.DUPLICATE) {
            System.out.println(
                "[M4-SIM] duplicate batch request ignored " + payload
            );
            return true;
        }
        if (result == BatchStartResult.CONFLICT) {
            System.out.println(
                "[M4-SIM] CONFLICT batch=" + runtime.activeBatchId +
                " existing=" + runtime.quantity + "/" +
                runtime.activeSizeCode + " received=" +
                requestSummary(payload)
            );
            return false;
        }
        if (result == BatchStartResult.ACTIVE_BATCH) {
            System.out.println(
                "[M4-SIM] ACTIVE batch=" + runtime.activeBatchId +
                " rejected new request " + payload
            );
            return false;
        }
        System.out.println("[M4-SIM] INVALID batch request " + payload);
        return false;
    }

    public static synchronized String nextRequest() {
        String bottleId = runtime.currentBottleId();
        int before = runtime.distributedCount();
        boolean distributed = bottleId != null &&
            Member4MachineStateV1.isContextDistributionComplete(
                bottleId, runtime.activeSizeCode
            );
        String request = runtime.tick(
            System.currentTimeMillis(), distributed
        );
        if (request != null && !bottleId.equals(lastLoggedBottle)) {
            System.out.println("[M4-SIM] recognising " + request);
            lastLoggedBottle = bottleId;
        }
        if (runtime.distributedCount() != before) {
            System.out.println(
                "[M4-SIM] context dispatched " + bottleId + " " +
                runtime.distributedCount() + "/" + runtime.quantity
            );
        }
        if (!terminalLogged &&
            (runtime.isFinished() || runtime.failureReason() != null)) {
            terminalLogged = true;
            System.out.println(runtime.isFinished() ?
                "[M4-SIM] FINISHED batch=" + runtime.activeBatchId +
                    " quantity=" + runtime.quantity :
                "[M4-SIM] STOPPED batch=" + runtime.activeBatchId +
                    ": " + runtime.failureReason());
        }
        return request;
    }

    private void startLegacyBatch(
        String prefix,
        int configuredQuantity,
        long firstBottleMillis
    ) {
        activeBatchId = prefix;
        activeSizeCode = legacySizeCode;
        quantity = configuredQuantity;
        distributed = 0;
        batchActive = true;
        requestActive = false;
        legacyIdentifiers = true;
        legacyBottleIdPrefix = prefix;
        nextBottleMillis = firstBottleMillis;
        nextRequestMillis = firstBottleMillis;
    }

    private static void resetRuntimeLogging() {
        lastLoggedBottle = null;
        terminalLogged = false;
    }

    private String defaultSizeCode() {
        return legacySizeCode == null ?
            M4BottleContextV1.SMALL : legacySizeCode;
    }

    private static boolean isSupportedSize(String value) {
        return M4BottleContextV1.SMALL.equals(value) ||
            M4BottleContextV1.LARGE.equals(value);
    }

    /**
     * Splits M1's canonical batchId|quantity|sizeCode request. The old
     * two-field form is still parsed so a Coordinator build that does not
     * publish a size yet keeps working; it takes the default size. Returns
     * null when the payload is neither shape.
     */
    private static String[] requestFields(String payload) {
        if (payload == null) {
            return null;
        }
        String[] fields = payload.split("\\|", -1);
        return fields.length == 3 || fields.length == 2 ? fields : null;
    }

    private static String legacyNote(String payload) {
        String[] fields = requestFields(payload);
        return fields != null && fields.length == 2 ?
            " (legacy request without a size code)" : "";
    }

    private static String requestSummary(String payload) {
        String[] fields = requestFields(payload);
        if (fields == null) {
            return "?";
        }
        return fields[1] + "/" + (fields.length == 3 ?
            fields[2] : runtime.defaultSizeCode());
    }

    private static long millis(
        Properties properties,
        String key,
        long fallback,
        long minimum
    ) {
        String value = properties.getProperty(key, String.valueOf(fallback));
        try {
            long result = Long.parseLong(value);
            if (result >= minimum && result <= Integer.MAX_VALUE) {
                return result;
            }
        }
        catch (NumberFormatException ignored) {
            // Report the property and allowed range below.
        }
        throw new IllegalArgumentException(
            key + " must be " + minimum + ".." + Integer.MAX_VALUE
        );
    }
}
