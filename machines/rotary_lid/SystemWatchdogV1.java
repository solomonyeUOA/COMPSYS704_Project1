import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Whole-system watchdog owned by the M3 fault-tolerance IP. */
public final class SystemWatchdogV1 {
    public static final long CHECK_INTERVAL_MS = 500L;
    public static final long STARTUP_GRACE_MS = 6000L;
    public static final long HEARTBEAT_TIMEOUT_MS = 4000L;
    public static final int MISSES_TO_FAULT = 3;
    public static final int MAX_AUTOMATIC_RESETS = 3;

    private static final Set<String> REQUIRED_LOCAL = new HashSet<String>();
    private static final Set<String> ARMED_EXTERNAL = new HashSet<String>();
    private static final Map<String, Observation> OBSERVATIONS =
        new HashMap<String, Observation>();
    private static final Map<String, Integer> MISSES =
        new HashMap<String, Integer>();
    private static final List<String> HISTORY = new ArrayList<String>();

    private static final BoundedStringSignalOfferV1 RESET_REQUEST =
        new BoundedStringSignalOfferV1(3, 500L, 100L);
    private static long resetRequestedAtMs = -1L;
    public static final long RESET_ACCEPT_TIMEOUT_MS = 8000L;

    private static volatile boolean active = Boolean.parseBoolean(
        System.getProperty("m3.watchdog.enabled", "true")
    );
    private static long startedAtMs = System.currentTimeMillis();
    private static long lastCheckMs;
    private static String health = active ? "WARNING" : "HEALTHY";
    private static String faultComponent = "None";
    private static String faultReason = "None";
    private static String action = active ? "Startup grace period" :
        "Monitoring disabled";
    private static int resetCount;
    private static int recoveryAttempt;
    private static long lastResetMs = -1L;
    private static long lastFaultMs = -1L;
    private static String pendingResetId;
    private static String lastObservedResetId;
    private static String recoveryComponent = "None";
    private static String recoveryReason = "None";
    private static boolean resetInProgress;
    private static boolean verifyingRecovery;
    private static int healthyVerificationSamples;
    private static volatile boolean safeError;
    private static long notificationSequence;
    private static String notificationTitle = "";
    private static String notificationMessage = "";

    static {
        REQUIRED_LOCAL.add("Fault Supervisor");
        REQUIRED_LOCAL.add("Rotary Controller");
        REQUIRED_LOCAL.add("Rotary Plant");
        REQUIRED_LOCAL.add("Lid Controller");
        REQUIRED_LOCAL.add("Lid Plant");
    }

    private SystemWatchdogV1() {
    }

    /** Controls the actual monitor. Disabling never clears a latched safe error. */
    public static synchronized void setActive(boolean enabled) {
        if (active == enabled) {
            return;
        }
        active = enabled;
        MISSES.clear();
        healthyVerificationSamples = 0;
        if (!enabled) {
            verifyingRecovery = false;
            health = safeError ? "FAULT" : "HEALTHY";
            action = safeError ?
                "SAFE / ERROR state; no safe backup available" :
                "Monitoring disabled";
            audit("WATCHDOG_TOGGLE", faultComponent, faultReason,
                "OFF", recoveryAttempt, "DISABLED", safeError);
            return;
        }
        startedAtMs = System.currentTimeMillis();
        health = safeError ? "FAULT" : "WARNING";
        action = safeError ?
            "SAFE / ERROR state; no safe backup available" :
            "Startup grace period";
        audit("WATCHDOG_TOGGLE", faultComponent, faultReason,
            "ON", recoveryAttempt, safeError ? "SAFE_ERROR" : "ACTIVE",
            safeError);
    }

    public static boolean isDriveFailoverEnabled() { return active && !safeError; }
    public static boolean isSafeError() { return safeError; }

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



    public static synchronized boolean requestManualSystemReset() {
        if (pendingResetId != null || M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
        long now = System.currentTimeMillis();
        safeError = false;
        recoveryAttempt = 0;
        recoveryComponent = "Operator request";
        recoveryReason = "Manual system reset";
        pendingResetId = "RST" + now;
        if (!RESET_REQUEST.begin(pendingResetId, now)) {
            pendingResetId = null;
            return false;
        }
        resetRequestedAtMs = now;
        lastResetMs = now;
        health = "RESETTING";
        action = "Manual system reset requested from M1";
        audit("MANUAL_RESET", recoveryComponent, recoveryReason,
            "Existing system reset", 0, "REQUESTED", false);
        return true;
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
        RESET_REQUEST.discard();
        resetRequestedAtMs = -1L;
        lastObservedResetId = resetId;
        resetCount++;
        lastResetMs = nowMs;
        resetInProgress = true;
        health = "RESETTING";
        action = "M1 reset received; waiting for M3 safe-state acknowledgement";
        pendingResetId = null;
        OBSERVATIONS.clear();
        MISSES.clear();
        audit("RESET_ACCEPTED", recoveryComponent, recoveryReason,
            "Existing system reset", recoveryAttempt, "IN_PROGRESS", false);
    }

    private static void tickAt(long nowMs) {
        if (pendingResetId != null) {
            if (nowMs - resetRequestedAtMs >= RESET_ACCEPT_TIMEOUT_MS) {
                RESET_REQUEST.discard();
                enterSafeError("Manual reset acknowledgement timeout", nowMs);
            } else {
                health = "RESETTING";
                action = "Manual reset requested; waiting for M1";
            }
            return;
        }
        if (!active) {
            if (resetInProgress) {
                if (M3SystemResetStateV1.isQuarantined()) {
                    health = "RESETTING";
                    return;
                }
                resetInProgress = false;
            }
            health = safeError ? "FAULT" : "HEALTHY";
            action = safeError ?
                "SAFE / ERROR state; no safe backup available" :
                "Monitoring disabled";
            return;
        }
        if (nowMs - lastCheckMs < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckMs = nowMs;

        if (safeError) {
            health = "FAULT";
            action = "SAFE / ERROR state; no safe backup available";
            return;
        }

        if (resetInProgress) {
            if (M3SystemResetStateV1.isQuarantined()) {
                health = "RESETTING";
                return;
            }
            resetInProgress = false;
            startedAtMs = nowMs;
            verifyingRecovery = true;
            healthyVerificationSamples = 0;
            health = "WARNING";
            action = "Reset complete; verifying component heartbeats";
        }

        Set<String> targets = new HashSet<String>(REQUIRED_LOCAL);
        targets.addAll(ARMED_EXTERNAL);
        boolean warning = false;
        String managedFaultComponent = null;
        String managedFaultReason = null;
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
                if (isManagedFaultComponent(component)) {
                    MISSES.remove(component);
                    warning = true;
                    managedFaultComponent = component;
                    managedFaultReason = value(
                        observation.detail, "supervised fault recovery"
                    );
                    continue;
                }
                if (recordMiss(component,
                    value(observation.detail, "reported unhealthy"), nowMs)) {
                    return;
                }
                warning = true;
            }
            else if (age >= HEARTBEAT_TIMEOUT_MS) {
                if (isManagedFaultComponent(component)) {
                    MISSES.remove(component);
                    warning = true;
                    managedFaultComponent = component;
                    managedFaultReason = "recovery heartbeat delayed (" +
                        age + " ms)";
                    continue;
                }
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

        if (verifyingRecovery) {
            faultComponent = recoveryComponent;
            faultReason = recoveryReason;
            if (warning) {
                healthyVerificationSamples = 0;
                health = "WARNING";
                action = "Verifying recovery; waiting for healthy heartbeats";
            }
            else {
                healthyVerificationSamples++;
                health = "WARNING";
                action = "Verifying recovery " + healthyVerificationSamples + "/2";
                if (healthyVerificationSamples >= 2) {
                    recoverySucceeded(nowMs);
                }
            }
            return;
        }

        faultComponent = managedFaultComponent == null ?
            "None" : managedFaultComponent;
        faultReason = managedFaultReason == null ?
            "None" : managedFaultReason;
        health = warning ? "WARNING" : "HEALTHY";
        action = managedFaultComponent == null ?
            (warning ? "Monitoring delayed or starting components" : "None") :
            "Fault isolated; recovery is controlled by Fault Supervisor";
    }

    private static boolean isManagedFaultComponent(String component) {
        String state = FaultSupervisorStateV2_1.stateName();
        if ("IDLE".equals(state) || "FAILED".equals(state)) {
            return false;
        }
        String subsystem = FaultSupervisorStateV2_1.activeSubsystem();
        return ("ROTARY".equals(subsystem) &&
                "Rotary Controller".equals(component)) ||
            ("LID".equals(subsystem) && "Lid Controller".equals(component)) ||
            ("TRANSFER".equals(subsystem) &&
                "M2 Transfer Link".equals(component));
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
        if (misses == 1) {
            audit("FAULT_SAMPLE", component, reason, "Observe",
                recoveryAttempt, "UNCONFIRMED", false);
        }
        if (misses < MISSES_TO_FAULT) {
            health = "WARNING";
            action = isCommunicationComponent(component) ?
                "Communication retry " + misses + "/" + MISSES_TO_FAULT :
                "Waiting for fault confirmation";
            return false;
        }

        lastFaultMs = nowMs;
        recoveryComponent = component;
        recoveryReason = reason;
        enterSafeError("No verified redundant process for this heartbeat failure", nowMs);
        return true;
    }

    private static boolean isCommunicationComponent(String component) {
        return component != null &&
            (component.endsWith(" Link") || component.contains("POS"));
    }

    private static void recoverySucceeded(long nowMs) {
        verifyingRecovery = false;
        healthyVerificationSamples = 0;
        health = "HEALTHY";
        faultComponent = "None";
        faultReason = "None";
        action = "Recovery verified; monitoring resumed";
        audit("WATCHDOG_RECOVERY", recoveryComponent, recoveryReason,
            "Existing system reset", recoveryAttempt, "SUCCESS", false);
        notifyGui(
            "Watchdog Recovery Successful",
            "Module: " + recoveryComponent + "\n" +
            "Action: Existing system reset\n" +
            "System resumed normally."
        );
        recoveryAttempt = 0;
        recoveryComponent = "None";
        recoveryReason = "None";
        startedAtMs = nowMs;
    }

    private static void enterSafeError(String result, long nowMs) {
        pendingResetId = null;
        resetInProgress = false;
        verifyingRecovery = false;
        safeError = true;
        health = "FAULT";
        faultComponent = recoveryComponent;
        faultReason = recoveryReason;
        action = "SAFE / ERROR state; no safe backup available";
        lastFaultMs = nowMs;
        audit("WATCHDOG_RECOVERY", recoveryComponent, recoveryReason,
            "Stop automatic recovery", recoveryAttempt, result, true);
        notifyGui(
            "Watchdog Recovery Failed",
            "Module: " + recoveryComponent + "\n" +
            result + ".\n" +
            "System has entered SAFE / ERROR state.\n" +
            "No automatic continuation is permitted."
        );
    }

    private static void audit(
        String faultType,
        String component,
        String reason,
        String attemptedAction,
        int attempt,
        String result,
        boolean safeStopRequired
    ) {
        String entry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(
            new Date()
        ) + " | faultType=" + value(faultType, "-") +
            " | module=" + value(component, "None") +
            " | watchdog=" + (active ? "ON" : "OFF") +
            " | reason=" + value(reason, "None") +
            " | action=" + value(attemptedAction, "None") +
            " | attempt=" + attempt + "/" + MAX_AUTOMATIC_RESETS +
            " | result=" + value(result, "-") +
            " | safeStopRequired=" + safeStopRequired;
        HISTORY.add(entry);
        while (HISTORY.size() > 200) {
            HISTORY.remove(0);
        }
        System.out.println("[WATCHDOG] " + entry);
    }

    private static void notifyGui(String title, String message) {
        notificationSequence++;
        notificationTitle = title;
        notificationMessage = message;
    }

    public static synchronized String[] historySnapshot() {
        return HISTORY.toArray(new String[HISTORY.size()]);
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(
            active, health, faultComponent, faultReason, action,
            resetCount, recoveryAttempt, lastResetMs, lastFaultMs,
            pendingResetId, safeError, notificationSequence,
            notificationTitle, notificationMessage
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
        recoveryAttempt = 0;
        lastResetMs = -1L;
        lastFaultMs = -1L;
        pendingResetId = null;
        lastObservedResetId = null;
        recoveryComponent = "None";
        recoveryReason = "None";
        resetInProgress = false;
        verifyingRecovery = false;
        healthyVerificationSamples = 0;
        safeError = false;
        notificationSequence = 0L;
        notificationTitle = "";
        notificationMessage = "";
        OBSERVATIONS.clear();
        ARMED_EXTERNAL.clear();
        MISSES.clear();
        HISTORY.clear();
        RESET_REQUEST.discard();
        resetRequestedAtMs = -1L;
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
        public final int recoveryAttempt;
        public final long lastResetMs;
        public final long lastFaultMs;
        public final String pendingResetId;
        public final boolean safeError;
        public final long notificationSequence;
        public final String notificationTitle;
        public final String notificationMessage;

        Snapshot(
            boolean active, String health, String faultComponent,
            String faultReason, String action, int resetCount,
            int recoveryAttempt, long lastResetMs, long lastFaultMs,
            String pendingResetId, boolean safeError,
            long notificationSequence, String notificationTitle,
            String notificationMessage
        ) {
            this.active = active;
            this.health = health;
            this.faultComponent = faultComponent;
            this.faultReason = faultReason;
            this.action = action;
            this.resetCount = resetCount;
            this.recoveryAttempt = recoveryAttempt;
            this.lastResetMs = lastResetMs;
            this.lastFaultMs = lastFaultMs;
            this.pendingResetId = pendingResetId;
            this.safeError = safeError;
            this.notificationSequence = notificationSequence;
            this.notificationTitle = notificationTitle;
            this.notificationMessage = notificationMessage;
        }
    }
}
