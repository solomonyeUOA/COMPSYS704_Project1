import java.util.HashMap;
import java.util.Map;

/** Unified read-only monitoring snapshot for the M3 GP and IP runtime. */
public final class FaultMonitoringStateV2_1 {
    public static final String SUPERVISOR = "Fault Supervisor";
    public static final String ROTARY_CONTROLLER = "Rotary Controller";
    public static final String ROTARY_PLANT = "Rotary Plant";
    public static final String LID_CONTROLLER = "Lid Controller";
    public static final String LID_PLANT = "Lid Plant";
    public static final String GUI_WORKER = "GUI Worker";
    public static final String M1_LINK = "M1 Coordinator Link";
    public static final String M2_LINK = "M2 Transfer Link";

    private static final long LATE_AFTER_MS = 1500L;
    private static final long UNRESPONSIVE_AFTER_MS = 4000L;
    private static final Map<String, Heartbeat> HEARTBEATS =
        new HashMap<String, Heartbeat>();

    private FaultMonitoringStateV2_1() {
    }

    public static synchronized void systemReset() {
        HEARTBEATS.clear();
    }

    public static synchronized void heartbeat(
        String component,
        boolean healthy,
        String detail
    ) {
        long now = System.currentTimeMillis();
        Heartbeat heartbeat = HEARTBEATS.get(component);
        if (heartbeat == null) {
            heartbeat = new Heartbeat();
            HEARTBEATS.put(component, heartbeat);
        }
        heartbeat.lastSeenMs = now;
        heartbeat.detail = detail;
        if (healthy) {
            heartbeat.lastHealthyMs = now;
        }
    }

    public static synchronized void peerTraffic(String component, String detail) {
        heartbeat(component, true, detail);
    }

    public static Snapshot snapshot() {
        long now = System.currentTimeMillis();
        Map<String, Heartbeat> heartbeats = heartbeatCopy();
        String supervisorState = FaultSupervisorStateV2_1.stateName();
        String decision = FaultSupervisorStateV2_1.decision();
        String subsystem = FaultSupervisorStateV2_1.activeSubsystem();
        String faultCode = FaultSupervisorStateV2_1.activeFaultCode();
        String severity = FaultSupervisorStateV2_1.activeSeverity();
        int rotaryStatus = Member3MachineStateV1.getRotaryStatus();
        int lidStatus = Member3MachineStateV1.getLidStatus();

        ComponentSnapshot[] components = new ComponentSnapshot[] {
            local(SUPERVISOR, "M3", supervisorState,
                healthOf(SUPERVISOR, heartbeats, now),
                heartbeats.get(SUPERVISOR), decision, now),
            local(ROTARY_CONTROLLER, "M3",
                Member3MachineStateV1.statusName(rotaryStatus),
                healthOf(ROTARY_CONTROLLER, heartbeats, now),
                heartbeats.get(ROTARY_CONTROLLER),
                "cycle=" + Member3MachineStateV1.getActiveCycleId() +
                    " motor=" + Member3MachineStateV1.isRotaryMotorEnabled(), now),
            local(ROTARY_PLANT, "M3", rotaryPlantState(),
                healthOf(ROTARY_PLANT, heartbeats, now),
                heartbeats.get(ROTARY_PLANT), Member3PlantStateV1.rotarySnapshot(), now),
            local(LID_CONTROLLER, "M3",
                Member3MachineStateV1.statusName(lidStatus),
                healthOf(LID_CONTROLLER, heartbeats, now),
                heartbeats.get(LID_CONTROLLER),
                "pick=" + Member3MachineStateV1.isLidPickEnabled() +
                    " place=" + Member3MachineStateV1.isLidPlaceEnabled(), now),
            local(LID_PLANT, "M3", Member3PlantStateV1.getLidActionName(),
                healthOf(LID_PLANT, heartbeats, now),
                heartbeats.get(LID_PLANT),
                "lids=" + Member3PlantStateV1.getLidMagazineCount() + "/" +
                    Member3PlantStateV1.getLidMagazineCapacity(), now),
            peer(M2_LINK, "M2", heartbeats.get(M2_LINK), now),
            peer(M1_LINK, "M1", heartbeats.get(M1_LINK), now),
            external("Filler A / B and Capper", "M4"),
            local(GUI_WORKER, "M3", "RUNNING",
                healthOf(GUI_WORKER, heartbeats, now),
                heartbeats.get(GUI_WORKER), "Swing refresh worker", now)
        };

        FaultSupervisorMetricsV2_1 metrics =
            FaultSupervisorStateV2_1.metricsSnapshot();
        String health = systemHealth(
            supervisorState, subsystem, rotaryStatus, lidStatus,
            components
        );
        int warnings = "WARNING".equals(severity) ||
            "RESOURCE".equals(severity) ? 1 : 0;
        int faults = "-".equals(faultCode) ? 0 : 1;
        int errors = metrics.rejectedMessages + metrics.recoveryFailures;

        return new Snapshot(
            now, health, "M3 LIVE / EXTERNAL PARTIAL", supervisorState,
            decision, subsystem, faultCode, severity,
            FaultSupervisorStateV2_1.activeBottleId(),
            FaultSupervisorStateV2_1.activeEventId(),
            FaultSupervisorStateV2_1.activeEpoch(),
            FaultSupervisorStateV2_1.activeStateVersion(),
            FaultSupervisorStateV2_1.latestStateVersion(),
            FaultSupervisorStateV2_1.activeAttempt(),
            FaultSupervisorStateV2_1.maximumAttempts(),
            FaultSupervisorStateV2_1.stateEnteredAtMs(),
            FaultSupervisorStateV2_1.policySummary(),
            FaultSupervisorStateV2_1.requiredSafeEvidence(),
            FaultSupervisorStateV2_1.requiredServiceEvidence(),
            FaultSupervisorStateV2_1.latestEvidence(),
            FaultSupervisorStateV2_1.localSummary(),
            metrics, warnings, errors, faults, components
        );
    }

    private static synchronized Map<String, Heartbeat> heartbeatCopy() {
        Map<String, Heartbeat> copy = new HashMap<String, Heartbeat>();
        for (Map.Entry<String, Heartbeat> entry : HEARTBEATS.entrySet()) {
            copy.put(entry.getKey(), new Heartbeat(entry.getValue()));
        }
        return copy;
    }

    private static String rotaryPlantState() {
        if (Member3MachineStateV1.isRotaryMotorEnabled()) {
            return "RUNNING";
        }
        return Member3PlantStateV1.canRotate() ? "READY" : "WAITING";
    }

    private static ComponentSnapshot local(
        String name,
        String owner,
        String state,
        String heartbeat,
        Heartbeat record,
        String detail,
        long now
    ) {
        return new ComponentSnapshot(
            name, owner, state, heartbeat,
            record == null ? -1L : now - record.lastSeenMs,
            record == null || record.lastHealthyMs <= 0L ?
                -1L : now - record.lastHealthyMs,
            detail
        );
    }

    private static ComponentSnapshot peer(
        String name,
        String owner,
        Heartbeat record,
        long now
    ) {
        return new ComponentSnapshot(
            name, owner, record == null ? "NO TRAFFIC" : "OBSERVED",
            "NO HEARTBEAT CONTRACT",
            record == null ? -1L : now - record.lastSeenMs,
            record == null || record.lastHealthyMs <= 0L ?
                -1L : now - record.lastHealthyMs,
            record == null ? "No protocol message observed" : record.detail
        );
    }

    private static ComponentSnapshot external(String name, String owner) {
        return new ComponentSnapshot(
            name, owner, "NOT MONITORED", "NO HEARTBEAT CONTRACT",
            -1L, -1L, "Outside the frozen M3 fault interface"
        );
    }

    private static String healthOf(
        String component,
        Map<String, Heartbeat> heartbeats,
        long now
    ) {
        Heartbeat heartbeat = heartbeats.get(component);
        if (heartbeat == null) {
            return "NOT OBSERVED";
        }
        long age = now - heartbeat.lastSeenMs;
        if (age >= UNRESPONSIVE_AFTER_MS) {
            return "UNRESPONSIVE";
        }
        if (age >= LATE_AFTER_MS) {
            return "LATE";
        }
        return "RESPONSIVE";
    }

    private static String systemHealth(
        String supervisorState,
        String subsystem,
        int rotaryStatus,
        int lidStatus,
        ComponentSnapshot[] components
    ) {
        if ("FAILED".equals(supervisorState) ||
            rotaryStatus == Member3MachineStateV1.FAULT ||
            lidStatus == Member3MachineStateV1.FAULT) {
            return "CRITICAL";
        }
        for (ComponentSnapshot component : components) {
            if ("UNRESPONSIVE".equals(component.heartbeat) &&
                "M3".equals(component.owner)) {
                return "CRITICAL";
            }
        }
        if (!"IDLE".equals(supervisorState) ||
            "TRANSFER".equals(subsystem)) {
            return "DEGRADED";
        }
        for (ComponentSnapshot component : components) {
            if ("LATE".equals(component.heartbeat)) {
                return "DEGRADED";
            }
        }
        return "HEALTHY";
    }

    private static final class Heartbeat {
        long lastSeenMs;
        long lastHealthyMs;
        String detail;

        Heartbeat() {
        }

        Heartbeat(Heartbeat source) {
            lastSeenMs = source.lastSeenMs;
            lastHealthyMs = source.lastHealthyMs;
            detail = source.detail;
        }
    }

    public static final class ComponentSnapshot {
        public final String name;
        public final String owner;
        public final String state;
        public final String heartbeat;
        public final long lastSeenAgeMs;
        public final long lastHealthyAgeMs;
        public final String detail;

        ComponentSnapshot(
            String name, String owner, String state, String heartbeat,
            long lastSeenAgeMs, long lastHealthyAgeMs, String detail
        ) {
            this.name = name;
            this.owner = owner;
            this.state = state;
            this.heartbeat = heartbeat;
            this.lastSeenAgeMs = lastSeenAgeMs;
            this.lastHealthyAgeMs = lastHealthyAgeMs;
            this.detail = detail;
        }
    }

    public static final class Snapshot {
        public final long capturedAtMs;
        public final String systemHealth;
        public final String visibility;
        public final String supervisorState;
        public final String decision;
        public final String subsystem;
        public final String faultCode;
        public final String severity;
        public final String bottleId;
        public final String eventId;
        public final String sourceEpoch;
        public final long eventStateVersion;
        public final long latestStateVersion;
        public final int attempt;
        public final int maximumAttempts;
        public final long stateEnteredAtMs;
        public final String policy;
        public final String requiredSafeEvidence;
        public final String requiredServiceEvidence;
        public final String latestEvidence;
        public final String localState;
        public final FaultSupervisorMetricsV2_1 metrics;
        public final int warnings;
        public final int errors;
        public final int faults;
        public final ComponentSnapshot[] components;

        Snapshot(
            long capturedAtMs, String systemHealth, String visibility,
            String supervisorState, String decision, String subsystem,
            String faultCode, String severity, String bottleId,
            String eventId, String sourceEpoch, long eventStateVersion,
            long latestStateVersion, int attempt, int maximumAttempts,
            long stateEnteredAtMs, String policy,
            String requiredSafeEvidence, String requiredServiceEvidence,
            String latestEvidence, String localState,
            FaultSupervisorMetricsV2_1 metrics, int warnings, int errors,
            int faults, ComponentSnapshot[] components
        ) {
            this.capturedAtMs = capturedAtMs;
            this.systemHealth = systemHealth;
            this.visibility = visibility;
            this.supervisorState = supervisorState;
            this.decision = decision;
            this.subsystem = subsystem;
            this.faultCode = faultCode;
            this.severity = severity;
            this.bottleId = bottleId;
            this.eventId = eventId;
            this.sourceEpoch = sourceEpoch;
            this.eventStateVersion = eventStateVersion;
            this.latestStateVersion = latestStateVersion;
            this.attempt = attempt;
            this.maximumAttempts = maximumAttempts;
            this.stateEnteredAtMs = stateEnteredAtMs;
            this.policy = policy;
            this.requiredSafeEvidence = requiredSafeEvidence;
            this.requiredServiceEvidence = requiredServiceEvidence;
            this.latestEvidence = latestEvidence;
            this.localState = localState;
            this.metrics = metrics;
            this.warnings = warnings;
            this.errors = errors;
            this.faults = faults;
            this.components = components;
        }
    }
}
