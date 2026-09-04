import java.util.Locale;
import java.util.Properties;

/** Finite, configurable environment stimulus for the simulation-only CD. */
public final class RecognitionSimulatorStateV1 {
    private static RecognitionSimulatorStateV1 runtime;
    private static String lastLoggedBottle;
    private static boolean terminalLogged;

    private final int quantity;
    private final String sizeCode;
    private final String bottleIdPrefix;
    private final long intervalMillis;
    private final long timeoutMillis;
    private final long requestGapMillis;
    private int distributed;
    private long nextBottleMillis;
    private long bottleStartedMillis;
    private long nextRequestMillis;
    private boolean active;
    private String failure;

    private RecognitionSimulatorStateV1(Properties properties, long nowMillis) {
        quantity = M4ProtocolV1.unsignedInteger(
            properties.getProperty("m4.sim.quantity", "1"), "m4.sim.quantity"
        );
        if (quantity == 0) {
            throw new IllegalArgumentException("m4.sim.quantity must be positive");
        }
        sizeCode = properties.getProperty("m4.sim.size", "S");
        if (!M4BottleContextV1.SMALL.equals(sizeCode) &&
            !M4BottleContextV1.LARGE.equals(sizeCode)) {
            throw new IllegalArgumentException("m4.sim.size must be S or L");
        }
        bottleIdPrefix = properties.getProperty("m4.sim.bottleIdPrefix", "SIM-B");
        M4ProtocolV1.validateBottleId(bottleIdPrefix);
        long delay = millis(properties, "m4.sim.startDelayMillis", 10000L, 0L);
        intervalMillis = millis(properties, "m4.sim.intervalMillis", 1000L, 0L);
        timeoutMillis = millis(properties, "m4.sim.timeoutMillis", 10000L, 1L);
        requestGapMillis = millis(properties, "m4.sim.requestGapMillis", 100L, 1L);
        if (requestGapMillis >= timeoutMillis) {
            throw new IllegalArgumentException(
                "m4.sim.requestGapMillis must be less than m4.sim.timeoutMillis"
            );
        }
        nextBottleMillis = nowMillis + delay;
    }

    public static RecognitionSimulatorStateV1 fromProperties(
        Properties properties, long nowMillis
    ) {
        return new RecognitionSimulatorStateV1(properties, nowMillis);
    }

    /** No catch-up bursts: at most one request is returned per call. */
    public String tick(long nowMillis, boolean contextDistributed) {
        if (isFinished() || failure != null || nowMillis < nextBottleMillis) {
            return null;
        }
        if (!active) {
            active = true;
            bottleStartedMillis = nowMillis;
            nextRequestMillis = nowMillis;
        }
        if (contextDistributed) {
            distributed++;
            active = false;
            nextBottleMillis = nowMillis + intervalMillis;
            return null;
        }
        if (nowMillis - bottleStartedMillis >= timeoutMillis) {
            failure = "context distribution timed out for " + currentBottleId();
            return null;
        }
        if (nowMillis < nextRequestMillis) {
            return null;
        }
        nextRequestMillis = nowMillis + requestGapMillis;
        return currentBottleId() + "|" + sizeCode;
    }

    public String currentBottleId() {
        return isFinished() ? null : bottleIdPrefix +
            String.format(Locale.ROOT, "%03d", distributed + 1);
    }

    public boolean isFinished() { return distributed == quantity; }
    public int distributedCount() { return distributed; }
    public String failureReason() { return failure; }

    public static synchronized void start() {
        runtime = fromProperties(System.getProperties(), System.currentTimeMillis());
        lastLoggedBottle = null;
        terminalLogged = false;
        System.out.println("[M4-SIM] started quantity=" + runtime.quantity +
            " size=" + runtime.sizeCode + " prefix=" + runtime.bottleIdPrefix +
            " (simulation input; does not receive POS orders)");
    }

    public static synchronized String nextRequest() {
        String bottleId = runtime.currentBottleId();
        int before = runtime.distributedCount();
        boolean distributed = bottleId != null &&
            Member4MachineStateV1.isContextDistributionComplete(
                bottleId, runtime.sizeCode
            );
        String request = runtime.tick(System.currentTimeMillis(), distributed);
        if (request != null && !bottleId.equals(lastLoggedBottle)) {
            System.out.println("[M4-SIM] recognising " + request);
            lastLoggedBottle = bottleId;
        }
        if (runtime.distributedCount() != before) {
            System.out.println("[M4-SIM] context dispatched " + bottleId +
                " " + runtime.distributedCount() + "/" + runtime.quantity);
        }
        if (!terminalLogged && (runtime.isFinished() || runtime.failureReason() != null)) {
            terminalLogged = true;
            System.out.println(runtime.isFinished() ?
                "[M4-SIM] FINISHED " + runtime.quantity + " bottle context(s)" :
                "[M4-SIM] STOPPED: " + runtime.failureReason());
        }
        return request;
    }

    private static long millis(Properties properties, String key, long fallback, long minimum) {
        String value = properties.getProperty(key, String.valueOf(fallback));
        try {
            long result = Long.parseLong(value);
            if (result >= minimum && result <= Integer.MAX_VALUE) {
                return result;
            }
        } catch (NumberFormatException ignored) {
            // Report the property name as well as the allowed range below.
        }
        throw new IllegalArgumentException(key + " must be " + minimum + ".." + Integer.MAX_VALUE);
    }
}
