import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Whole-system watchdog owned by the M3 fault-tolerance IP. */
public final class SystemWatchdogV1 {
    public static final long CHECK_INTERVAL_MS = 500L;
    public static final long STARTUP_GRACE_MS = 6000L;
    public static final long HEARTBEAT_TIMEOUT_MS = 4000L;
    public static final int MISSES_TO_FAULT = 3;
    public static final long RESET_COOLDOWN_MS = 30000L;
    public static final int MAX_AUTOMATIC_RESETS = 3;

    private static final Set<String> REQUIRED_LOCAL = new HashSet<String>();
    private static final Set<String> ARMED_EXTERNAL = new HashSet<String>();
    private static final Map<String, Observation> OBSERVATIONS =
        new HashMap<String, Observation>();
    private static final Map<String, Integer> MISSES =
        new HashMap<String, Integer>();
    private static final BoundedStringSignalOfferV1 RESET_REQUEST =
        new BoundedStringSignalOfferV1(3, 500L, 100L);

    private static boolean active = Boolean.parseBoolean(
        System.getProperty("m3.watchdog.enabled", "true")
    );
    private static long startedAtMs = System.currentTimeMillis();
    private static long lastCheckMs;
    private static String health = active ? "WARNING" : "HEALTHY";
    private static String faultComponent = "None";
    private static String faultReason = "None";
    private static String action = active ? "Startup grace period" : "None";
    private static int resetCount;
    private static long lastResetMs = -1L;
    private static long lastFaultMs = -1L;
    private static String pendingResetId;
    private static String lastObservedResetId;
    private static boolean resetInProgress;

    static {
        REQUIRED_LOCAL.add("Fault Supervisor");
        REQUIRED_LOCAL.add("Rotary Controller");
        REQUIRED_LOCAL.add("Rotary Plant");
        REQUIRED_LOCAL.add("Lid Controller");
        REQUIRED_LOCAL.add("Lid Plant");
    }

    private SystemWatchdogV1() {
    }

    public static synchronized void observe(
        String component,
        boolean healthy,
        String detail
    ) {
        observeAt(component, healthy, detail, System.currentTimeMillis());
    }

    public static synchronized void observeExternal(
        String component,
        String payload
    ) {
        if (payload == null || payload.trim().length() == 0) {
            return;
        }
        ARMED_EXTERNAL.add(component);
        observeAt(component, true, payload.trim(), System.currentTimeMillis());
    }

    static synchronized void observeAt(
        String component,
        boolean healthy,
        String detail,
        long nowMs
    ) {
        Observation observation = OBSERVATIONS.get(component);
        if (observation == null) {
            observation = new Observation();
            OBSERVATIONS.put(component, observation);
        }
        observation.lastSeenMs = nowMs;
        observation.healthy = healthy;
        observation.detail = detail == null ? "" : detail;
        if (healthy) {
            observation.lastHealthyMs = nowMs;
        }
    }

    public static synchronized String nextSystemResetRequest() {
        long now = System.currentTimeMillis();
        tickAt(now);
        return RESET_REQUEST.nextValue(now);
    }

    public static synchronized void onSystemResetAccepted(String resetId) {
        onSystemResetAcceptedAt(resetId, System.currentTimeMillis());
    }

    static synchronized void onSystemResetAcceptedAt(
        String resetId,
        long nowMs
    ) {
        if (resetId == null || resetId.equals(lastObservedResetId)) {
            return;
        }
        lastObservedResetId = resetId;
        lastResetMs = nowMs;
        resetInProgress = true;
        health = "RESETTING";
        action = "M1 reset received; waiting for M3 safe-state acknowledgement";
        RESET_REQUEST.discard();
        OBSERVATIONS.clear();
        MISSES.clear();
    }

    private static void tickAt(long nowMs) {
        if (!active) {
            health = "HEALTHY";
            action = "None";
            return;
        }
        if (nowMs - lastCheckMs < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckMs = nowMs;

        if (resetInProgress) {
            if (M3SystemResetStateV1.isQuarantined()) {
                health = "RESETTING";
                return;
            }
            resetInProgress = false;
            startedAtMs = nowMs;
            pendingResetId = null;
            health = "WARNING";
            action = "Reset complete; verifying component heartbeats";
        }

        if (pendingResetId != null) {
            health = "RESETTING";
            action = "Automatic reset requested; waiting for M1";
            return;
        }

        Set<String> targets = new HashSet<String>(REQUIRED_LOCAL);
        targets.addAll(ARMED_EXTERNAL);
        boolean warning = false;
        for (String component : targets) {
            Observation observation = OBSERVATIONS.get(component);
            if (observation == null) {
                if (nowMs - startedAtMs < STARTUP_GRACE_MS) {
                    warning = true;
                    continue;
                }
                if (recordMiss(component, "heartbeat not received", nowMs)) {
                    return;
                }
                warning = true;
                continue;
            }
            long age = Math.max(0L, nowMs - observation.lastSeenMs);
            if (!observation.healthy) {
                if (recordMiss(component,
                    value(observation.detail, "reported unhealthy"), nowMs)) {
                    return;
                }
                warning = true;
            }
            else if (age >= HEARTBEAT_TIMEOUT_MS) {
                if (recordMiss(component, "heartbeat timeout (" + age + " ms)",
                    nowMs)) {
                    return;
                }
                warning = true;
            }
            else {
                MISSES.remove(component);
                if (age >= HEARTBEAT_TIMEOUT_MS / 2L) {
                    warning = true;
                }
            }
        }

        faultComponent = "None";
        faultReason = "None";
        health = warning ? "WARNING" : "HEALTHY";
        action = warning ? "Monitoring delayed or starting components" : "None";
    }

    private static boolean recordMiss(
        String component,
        String reason,
        long nowMs
    ) {
        int misses = MISSES.containsKey(component) ?
            MISSES.get(component).intValue() + 1 : 1;
        MISSES.put(component, Integer.valueOf(misses));
        faultComponent = component;
        faultReason = reason + "; miss " + misses + "/" + MISSES_TO_FAULT;
        if (misses < MISSES_TO_FAULT) {
            health = "WARNING";
            action = "Waiting for fault confirmation";
            return false;
        }

        health = "FAULT";
        lastFaultMs = nowMs;
        if (resetCount >= MAX_AUTOMATIC_RESETS) {
            action = "Automatic reset limit reached; manual intervention required";
            return true;
        }
        if (lastResetMs >= 0L && nowMs - lastResetMs < RESET_COOLDOWN_MS) {
            action = "Reset suppressed during cooldown; manual intervention required";
            return true;
        }

        pendingResetId = "RST" + nowMs;
        RESET_REQUEST.begin(pendingResetId, nowMs);
        resetCount++;
        lastResetMs = nowMs;
        health = "RESETTING";
        action = "Automatic reset requested from M1";
        return true;
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(
            active, health, faultComponent, faultReason, action,
            resetCount, lastResetMs, lastFaultMs, pendingResetId
        );
    }

    static synchronized void resetForTest(long nowMs) {
        active = true;
        startedAtMs = nowMs;
        lastCheckMs = nowMs - CHECK_INTERVAL_MS;
        health = "WARNING";
        faultComponent = "None";
        faultReason = "None";
        action = "Startup grace period";
        resetCount = 0;
        lastResetMs = -1L;
        lastFaultMs = -1L;
        pendingResetId = null;
        lastObservedResetId = null;
        resetInProgress = false;
        OBSERVATIONS.clear();
        ARMED_EXTERNAL.clear();
        MISSES.clear();
        RESET_REQUEST.discard();
    }

    static synchronized void tickForTest(long nowMs) {
        tickAt(nowMs);
    }

    private static String value(String text, String fallback) {
        return text == null || text.length() == 0 ? fallback : text;
    }

    private static final class Observation {
        long lastSeenMs;
        long lastHealthyMs;
        boolean healthy;
        String detail;
    }

    public static final class Snapshot {
        public final boolean active;
        public final String health;
        public final String faultComponent;
        public final String faultReason;
        public final String action;
        public final int resetCount;
        public final long lastResetMs;
        public final long lastFaultMs;
        public final String pendingResetId;

        Snapshot(
            boolean active, String health, String faultComponent,
            String faultReason, String action, int resetCount,
            long lastResetMs, long lastFaultMs, String pendingResetId
        ) {
            this.active = active;
            this.health = health;
            this.faultComponent = faultComponent;
            this.faultReason = faultReason;
            this.action = action;
            this.resetCount = resetCount;
            this.lastResetMs = lastResetMs;
            this.lastFaultMs = lastFaultMs;
            this.pendingResetId = pendingResetId;
        }
    }
}
