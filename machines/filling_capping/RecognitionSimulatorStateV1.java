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

    private final String sizeCode;
    private final long intervalMillis;
    private final long timeoutMillis;
    private final long requestGapMillis;
    private String activeBatchId;
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

    private RecognitionSimulatorStateV1(Properties properties) {
        sizeCode = properties.getProperty("m4.sim.size", "S");
        if (!M4BottleContextV1.SMALL.equals(sizeCode) &&
            !M4BottleContextV1.LARGE.equals(sizeCode)) {
            throw new IllegalArgumentException("m4.sim.size must be S or L");
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
            new RecognitionSimulatorStateV1(properties);
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

    /** Preferred integrated mode: starts idle and ignores m4.sim.quantity. */
    public static RecognitionSimulatorStateV1 batchDrivenFromProperties(
        Properties properties,
        long nowMillis
    ) {
        return new RecognitionSimulatorStateV1(properties);
    }

    public BatchStartResult startBatch(String batchId, int requestedQuantity) {
        return startBatch(
            batchId,
            requestedQuantity,
            System.currentTimeMillis()
        );
    }

    public BatchStartResult startBatch(
        String batchId,
        int requestedQuantity,
        long nowMillis
    ) {
        try {
            M4ProtocolV1.validateBottleId(batchId);
        }
        catch (IllegalArgumentException invalid) {
            return BatchStartResult.INVALID;
        }
        if (requestedQuantity < 1) {
            return BatchStartResult.INVALID;
        }

        if (batchId.equals(activeBatchId)) {
            return requestedQuantity == quantity ?
                BatchStartResult.DUPLICATE : BatchStartResult.CONFLICT;
        }
        // Only a batch that is still running may block the next one. A
        // terminated batch - finished or failed - must never wedge the
        // simulator for the rest of the run.
        if (batchActive) {
            return BatchStartResult.ACTIVE_BATCH;
        }

        activeBatchId = batchId;
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

    public BatchStartResult startBatchPayload(
        String payload,
        long nowMillis
    ) {
        try {
            String[] fields = M4ProtocolV1.fields(payload, 2);
            int requestedQuantity = M4ProtocolV1.unsignedInteger(
                fields[1], "quantity"
            );
            return startBatch(fields[0], requestedQuantity, nowMillis);
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
        return currentBottleId() + "|" + sizeCode;
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
            runtime.quantity + " size=" + runtime.sizeCode + " prefix=" +
            runtime.legacyBottleIdPrefix
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
            "M4_SIM_BATCH_REQUEST (m4.sim.quantity ignored)"
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
                " quantity=" + runtime.quantity
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
                " existing=" + runtime.quantity + " received=" +
                receivedQuantity(payload)
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
                bottleId, runtime.sizeCode
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

    private static String receivedQuantity(String payload) {
        if (payload == null) {
            return "?";
        }
        int separator = payload.lastIndexOf('|');
        return separator < 0 ? "?" : payload.substring(separator + 1);
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
