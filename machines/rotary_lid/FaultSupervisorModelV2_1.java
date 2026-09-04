import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deterministic policy, correlation and evidence model for the M3 IP. */
public final class FaultSupervisorModelV2_1 {
    public enum State {
        IDLE,
        WAITING_SAFE_STOP,
        WAITING_ACK,
        WAITING_RESULT,
        RESOURCE_WAIT,
        RECOVERY_READY,
        MANUAL_RECOVERY,
        LOCKED_OUT,
        FAILED
    }

    private final Map<String, String> priorEvents =
        new HashMap<String, String>();
    private final Map<String, String> priorAcks =
        new HashMap<String, String>();
    private final Map<String, String> priorResults =
        new HashMap<String, String>();
    private final List<String> history = new ArrayList<String>();
    private final Map<String, String> localActiveEvents =
        new HashMap<String, String>();
    private final Map<String, String> localDecisions =
        new HashMap<String, String>();
    private final Map<String, Integer> localRetryCounts =
        new HashMap<String, Integer>();

    private String activeEpoch;
    private long latestStateVersion = -1;
    private FaultProtocolV2_1.FaultEvent activeEvent;
    private FaultPolicyV2_1 activePolicy;
    private State state = State.IDLE;
    private int activeAttempt;
    private boolean manualEvidenceRecorded;
    private String decision = "IDLE";
    private String latestEvidence = "NONE";

    private String pendingRecoveryRequest;
    private String pendingFaultAlert;
    private String pendingSafeStopRequest;
    private String pendingRecoveryReady;
    private String pendingRecoveryFailed;

    private int validEvents;
    private int rejectedMessages;
    private int duplicateMessages;
    private int automaticAttempts;
    private int verifiedRecoveries;
    private int resourceWaits;
    private int manualEscalations;
    private int recoveryFailures;
    private long traceSequence;

    public synchronized boolean onFaultEvent(String payload) {
        FaultProtocolV2_1.FaultEvent event;
        FaultPolicyV2_1 policy;
        try {
            event = FaultProtocolV2_1.parseFaultEvent(payload);
            policy = FaultPolicyV2_1.select(event);
        }
        catch (IllegalArgumentException exception) {
            reject("INVALID_EVENT " + exception.getMessage());
            return false;
        }

        String key = eventKey(event.sourceEpoch, event.eventId);
        if (priorEvents.containsKey(key)) {
            boolean identical = priorEvents.get(key).equals(payload);
            duplicateMessages++;
            record((identical ? "DUPLICATE_EVENT " :
                "CONFLICTING_EVENT_ID ") + key);
            if (!identical) {
                reject("CONFLICTING_EVENT_ID " + key);
            }
            return identical;
        }

        if (activeEpoch != null && !activeEpoch.equals(event.sourceEpoch)) {
            record("NEW_EPOCH " + event.sourceEpoch + " invalidated " +
                activeEpoch);
            clearActiveRecovery();
            latestStateVersion = -1;
        }
        if (event.stateVersion < latestStateVersion) {
            reject("STALE_STATE " + key);
            return false;
        }
        if (latestStateVersion >= 0 &&
            event.stateVersion > latestStateVersion + 1) {
            reject("STATE_SNAPSHOT_REQUIRED " + key);
            return false;
        }
        if (hasActiveRecovery() && activeEvent != null &&
            !activeEvent.eventId.equals(event.eventId)) {
            priorEvents.put(key, payload);
            validEvents++;
            pendingFaultAlert = payload;
            if (policy.requiresSafeStop) {
                pendingSafeStopRequest = safeStopRequest(event);
            }
            pendingRecoveryFailed = recoveryFailed(
                event, "CONCURRENT_EVENT_HELD"
            );
            record("CONCURRENT_EVENT_HELD " + key);
            return false;
        }

        priorEvents.put(key, payload);
        validEvents++;
        activeEpoch = event.sourceEpoch;
        latestStateVersion = event.stateVersion;
        activeEvent = event;
        activePolicy = policy;
        activeAttempt = 0;
        manualEvidenceRecorded = false;
        pendingFaultAlert = payload;
        latestEvidence = "FAULT_EVENT_VALIDATED";
        record("FAULT " + key + " " + policy.summary());

        if (policy.disposition ==
            FaultPolicyV2_1.Disposition.AUTOMATIC_RETRY) {
            if (policy.requiresSafeStop) {
                state = State.WAITING_SAFE_STOP;
                decision = "WAITING_FOR_M1_SAFE_STOP";
                pendingSafeStopRequest = safeStopRequest(event);
            }
            else {
                issueRecoveryRequest();
            }
        }
        else if (policy.disposition ==
            FaultPolicyV2_1.Disposition.RESOURCE_WAIT) {
            state = State.RESOURCE_WAIT;
            decision = "WAIT_RESOURCE_NO_RETRY_BUDGET";
            resourceWaits++;
        }
        else {
            state = State.WAITING_SAFE_STOP;
            decision = "NO_BLIND_RETRY_WAITING_SAFE_STOP";
            pendingSafeStopRequest = safeStopRequest(event);
            manualEscalations++;
        }
        return true;
    }

    public synchronized boolean onTransferFault(String payload) {
        FaultProtocolV2_1.FaultEvent event;
        try {
            event = FaultProtocolV2_1.parseFaultEvent(payload);
        }
        catch (IllegalArgumentException exception) {
            reject("INVALID_EVENT " + exception.getMessage());
            return false;
        }
        if (!"TRANSFER".equals(event.subsystem)) {
            reject("WRONG_SUBSYSTEM " + event.subsystem);
            return false;
        }
        return onFaultEvent(payload);
    }

    /** Accepts M1 acknowledgement only after an independent safe stop. */
    public synchronized boolean onSafeStopAck(String payload) {
        String[] fields;
        try {
            fields = coordinationFields(payload, 5);
        }
        catch (IllegalArgumentException exception) {
            reject("INVALID_SAFE_STOP_ACK " + exception.getMessage());
            return false;
        }
        if (state != State.WAITING_SAFE_STOP || activeEvent == null ||
            !activeEvent.eventId.equals(fields[1]) ||
            !activeEvent.sourceEpoch.equals(fields[2]) ||
            !"SAFE_STOPPED".equals(fields[3]) ||
            activeEvent.stateVersion != unsignedLong(fields[4])) {
            reject("STALE_OR_MISMATCHED_SAFE_STOP_ACK");
            return false;
        }
        latestEvidence = "M1_SAFE_STOP_ACK";
        record("SAFE_STOP_ACK " + fields[1]);
        if (activePolicy.disposition ==
            FaultPolicyV2_1.Disposition.AUTOMATIC_RETRY) {
            issueRecoveryRequest();
        }
        else {
            state = State.LOCKED_OUT;
            decision = "MANUAL_RECONCILIATION_REQUIRED";
            queueRecoveryFailed("NO_AUTOMATIC_ACTION");
        }
        return true;
    }

    /** Compatibility helper used by older model tests. */
    public synchronized boolean confirmSafeStop(
        String eventId,
        boolean independentEvidence
    ) {
        if (!independentEvidence || activeEvent == null) {
            return false;
        }
        return onSafeStopAck(
            "V2|" + eventId + "|" + activeEvent.sourceEpoch +
            "|SAFE_STOPPED|" + activeEvent.stateVersion
        );
    }

    public synchronized boolean onRecoveryAck(String payload) {
        FaultProtocolV2_1.RecoveryAck ack;
        try {
            ack = FaultProtocolV2_1.parseRecoveryAck(payload);
        }
        catch (IllegalArgumentException exception) {
            failRecovery("INVALID_ACK " + exception.getMessage());
            return false;
        }
        String key = eventKey(ack.sourceEpoch, ack.eventId) + "|" +
            ack.attempt;
        if (priorAcks.containsKey(key)) {
            boolean identical = priorAcks.get(key).equals(payload);
            duplicateMessages++;
            record((identical ? "DUPLICATE_ACK " : "CONFLICTING_ACK ") +
                key);
            if (!identical) {
                failRecovery("CONFLICTING_ACK " + key);
            }
            return identical;
        }
        if (state != State.WAITING_ACK ||
            !matchesActive(ack.eventId, ack.sourceEpoch, ack.attempt) ||
            ack.acceptedStateVersion != activeEvent.stateVersion) {
            failRecovery("STALE_OR_MISMATCHED_ACK " + key);
            return false;
        }
        priorAcks.put(key, payload);
        if (!"ACCEPTED".equals(ack.ack)) {
            failRecovery("RECOVERY_REJECTED " + ack.reason);
            return false;
        }
        state = State.WAITING_RESULT;
        decision = "RECOVERY_IN_PROGRESS";
        latestEvidence = "CONTROLLER_ACK " + ack.reason;
        record("ACK " + key + " " + ack.reason);
        return true;
    }

    public synchronized boolean onRecoveryResult(String payload) {
        FaultProtocolV2_1.RecoveryResult result;
        try {
            result = FaultProtocolV2_1.parseRecoveryResult(payload);
        }
        catch (IllegalArgumentException exception) {
            failRecovery("INVALID_RESULT " + exception.getMessage());
            return false;
        }
        String key = eventKey(result.sourceEpoch, result.eventId) + "|" +
            result.attempt;
        if (priorResults.containsKey(key)) {
            boolean identical = priorResults.get(key).equals(payload);
            duplicateMessages++;
            record((identical ? "DUPLICATE_RESULT " :
                "CONFLICTING_RESULT ") + key);
            if (!identical) {
                failRecovery("CONFLICTING_RESULT " + key);
            }
            return identical;
        }
        if (state != State.WAITING_RESULT ||
            !matchesActive(result.eventId, result.sourceEpoch,
                result.attempt)) {
            failRecovery("STALE_OR_MISMATCHED_RESULT " + key);
            return false;
        }
        priorResults.put(key, payload);
        boolean safe = containsAllEvidence(
            result.safeEvidence,
            activePolicy.safeEvidence
        );
        boolean service = containsAllEvidence(
            result.serviceEvidence,
            activePolicy.serviceEvidence
        );
        if (!"SUCCESS".equals(result.outcome) || !safe || !service ||
            result.resultingStateVersion <= activeEvent.stateVersion) {
            failRecovery("INVALID_RECOVERY_EVIDENCE " + key);
            return false;
        }
        latestStateVersion = result.resultingStateVersion;
        state = State.RECOVERY_READY;
        decision = "VERIFIED_READY_AWAIT_M1";
        latestEvidence = result.safeEvidence + ";" +
            result.serviceEvidence;
        verifiedRecoveries++;
        pendingRecoveryReady = recoveryReady(result.resultingStateVersion);
        record("RECOVERY_READY " + key);
        return true;
    }

    public synchronized boolean recordManualEvidence(
        ManualReconciliationEvidenceV2_1 evidence
    ) {
        if (evidence == null || activeEvent == null ||
            (state != State.LOCKED_OUT &&
                state != State.MANUAL_RECOVERY) ||
            !activeEvent.eventId.equals(evidence.eventId) ||
            !activeEvent.sourceEpoch.equals(evidence.sourceEpoch) ||
            !activeEvent.subsystem.equals(evidence.subsystem) ||
            !activeEvent.bottleId.equals(evidence.bottleId) ||
            activeEvent.stateVersion != evidence.stateVersion) {
            reject("INVALID_MANUAL_EVIDENCE");
            return false;
        }
        manualEvidenceRecorded = true;
        latestEvidence = "MANUAL " + evidence.evidenceCode + " by " +
            evidence.operatorId;
        decision = "AWAIT_NEWER_CONTROLLER_EVIDENCE";
        record("MANUAL_EVIDENCE " + evidence.eventId + " " +
            evidence.evidenceCode + " operator=" + evidence.operatorId);
        return true;
    }

    public synchronized boolean confirmManualControllerEvidence(
        String eventId,
        String sourceEpoch,
        String safeEvidence,
        String serviceEvidence,
        long resultingStateVersion
    ) {
        if (!manualEvidenceRecorded || activeEvent == null ||
            state != State.LOCKED_OUT ||
            !activeEvent.eventId.equals(eventId) ||
            !activeEvent.sourceEpoch.equals(sourceEpoch) ||
            resultingStateVersion <= activeEvent.stateVersion ||
            !containsAllEvidence(safeEvidence, activePolicy.safeEvidence) ||
            !containsAllEvidence(serviceEvidence,
                activePolicy.serviceEvidence)) {
            reject("INVALID_MANUAL_CONTROLLER_EVIDENCE");
            return false;
        }
        latestStateVersion = resultingStateVersion;
        state = State.RECOVERY_READY;
        decision = "VERIFIED_READY_AWAIT_M1";
        latestEvidence = safeEvidence + ";" + serviceEvidence;
        verifiedRecoveries++;
        pendingRecoveryReady = recoveryReady(resultingStateVersion);
        record("MANUAL_RECOVERY_READY " + eventId);
        return true;
    }

    public synchronized boolean confirmResourceRestored(
        String eventId,
        boolean lidAvailable,
        long resultingStateVersion
    ) {
        if (state != State.RESOURCE_WAIT || activeEvent == null ||
            !activeEvent.eventId.equals(eventId) || !lidAvailable ||
            resultingStateVersion <= activeEvent.stateVersion) {
            reject("INVALID_RESOURCE_EVIDENCE");
            return false;
        }
        latestStateVersion = resultingStateVersion;
        state = State.RECOVERY_READY;
        decision = "RESOURCE_RESTORED_AWAIT_M1";
        latestEvidence = "lid_available";
        verifiedRecoveries++;
        pendingRecoveryReady = recoveryReady(resultingStateVersion);
        record("RESOURCE_READY " + eventId);
        return true;
    }

    public synchronized boolean onResumeDecision(String payload) {
        String[] fields;
        try {
            fields = coordinationFields(payload, 6);
        }
        catch (IllegalArgumentException exception) {
            reject("INVALID_RESUME_DECISION " + exception.getMessage());
            return false;
        }
        if (activeEvent == null ||
            !activeEvent.eventId.equals(fields[1]) ||
            !activeEvent.sourceEpoch.equals(fields[2]) ||
            unsignedLong(fields[5]) != latestStateVersion) {
            reject("STALE_OR_MISMATCHED_RESUME_DECISION");
            return false;
        }
        if ("HOLD".equals(fields[3])) {
            decision = "M1_HOLD " + fields[4];
            record(decision);
            return true;
        }
        if (!"RESUME".equals(fields[3]) || state != State.RECOVERY_READY) {
            reject("UNSAFE_RESUME_DECISION");
            return false;
        }
        record("M1_RESUME " + fields[4]);
        clearActiveRecovery();
        return true;
    }

    public synchronized void reportAckTimeout() {
        if (state == State.WAITING_ACK) {
            failRecovery("ACK_TIMEOUT_NO_RESEND");
        }
    }

    public synchronized void reportResultTimeout() {
        if (state == State.WAITING_RESULT) {
            failRecovery("RESULT_TIMEOUT");
        }
    }

    public synchronized void observeRotaryFault(
        String eventId,
        String reason
    ) {
        if (sameLocalEvent("ROTARY", eventId)) {
            return;
        }
        localActiveEvents.put("ROTARY", eventId);
        localDecisions.put("ROTARY",
            "SAFE_STOP_RECONCILE_POSITION_NO_AUTO_REHOME");
        record("LOCAL_FAULT " + eventId + " ROTARY " + reason);
    }

    public synchronized void observeLidFault(
        String eventId,
        LidLoaderControllerModelV1.Fault fault
    ) {
        if (sameLocalEvent("LID", eventId)) {
            return;
        }
        localActiveEvents.put("LID", eventId);
        if (fault == LidLoaderControllerModelV1.Fault.MAGAZINE_EMPTY) {
            localDecisions.put("LID", "WAIT_RESOURCE");
        }
        else if (fault == LidLoaderControllerModelV1.Fault.PICK_TIMEOUT) {
            localDecisions.put("LID", "AWAIT_PICK_RETRY_EVIDENCE");
        }
        else {
            localDecisions.put("LID", "MANUAL_LID_RECONCILIATION");
        }
        record("LOCAL_FAULT " + eventId + " LID " + fault.name());
    }

    public synchronized boolean authorizeRotaryReset(
        String eventId,
        RotaryRecoveryEvidenceV1 evidence
    ) {
        if (!matchesLocalEvent("ROTARY", eventId) || evidence == null ||
            !evidence.permitsReset()) {
            record("LOCAL_RESET_REJECTED ROTARY " + eventId);
            return false;
        }
        localDecisions.put("ROTARY", "RESET_AFTER_MANUAL_RECONCILIATION");
        record("LOCAL_RESET_AUTHORIZED ROTARY " + eventId);
        return true;
    }

    public synchronized boolean authorizeLidReset(
        String eventId,
        LidLoaderControllerModelV1.Fault fault,
        LidRecoveryEvidenceV1 evidence
    ) {
        if (!matchesLocalEvent("LID", eventId) || evidence == null ||
            !evidence.permitsReset(fault)) {
            record("LOCAL_RESET_REJECTED LID " + eventId);
            return false;
        }
        if (fault == LidLoaderControllerModelV1.Fault.PICK_TIMEOUT) {
            int attempts = localRetryCounts.containsKey(eventId) ?
                localRetryCounts.get(eventId).intValue() : 0;
            if (attempts >= 1) {
                localDecisions.put("LID", "PICK_RETRY_LIMIT_REACHED");
                record("LOCAL_RESET_REJECTED LID RETRY_LIMIT " + eventId);
                return false;
            }
            localRetryCounts.put(eventId, Integer.valueOf(attempts + 1));
            localDecisions.put("LID", "ONE_PICK_RETRY_AUTHORIZED");
        }
        else {
            localDecisions.put("LID", "RESET_AFTER_EVIDENCE");
        }
        record("LOCAL_RESET_AUTHORIZED LID " + eventId);
        return true;
    }

    public synchronized void resolveLocalFault(
        String subsystem,
        String eventId
    ) {
        if (!matchesLocalEvent(subsystem, eventId)) {
            return;
        }
        localActiveEvents.remove(subsystem);
        localDecisions.put(subsystem, "READY");
        record("LOCAL_FAULT_RESOLVED " + subsystem + " " + eventId);
    }

    public synchronized String takeRecoveryRequest() {
        String result = pendingRecoveryRequest;
        pendingRecoveryRequest = null;
        return result;
    }

    public synchronized String takeFaultAlert() {
        String result = pendingFaultAlert;
        pendingFaultAlert = null;
        return result;
    }

    public synchronized String takeSafeStopRequest() {
        String result = pendingSafeStopRequest;
        pendingSafeStopRequest = null;
        return result;
    }

    public synchronized String takeRecoveryReady() {
        String result = pendingRecoveryReady;
        pendingRecoveryReady = null;
        return result;
    }

    public synchronized String takeRecoveryFailed() {
        String result = pendingRecoveryFailed;
        pendingRecoveryFailed = null;
        return result;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized String getDecision() {
        return decision;
    }

    public synchronized String getActiveEventId() {
        return activeEvent == null ? "-" : activeEvent.eventId;
    }

    public synchronized String getActiveEpoch() {
        return activeEvent == null ? "-" : activeEvent.sourceEpoch;
    }

    public synchronized String getActiveSubsystem() {
        return activeEvent == null ? "-" : activeEvent.subsystem;
    }

    public synchronized String getActiveFaultCode() {
        return activeEvent == null ? "-" : activeEvent.faultCode;
    }

    public synchronized String getActiveSeverity() {
        return activeEvent == null ? "-" : activeEvent.severity;
    }

    public synchronized String getActiveBottleId() {
        return activeEvent == null ? "-" : activeEvent.bottleId;
    }

    public synchronized long getActiveStateVersion() {
        return activeEvent == null ? -1 : activeEvent.stateVersion;
    }

    public synchronized int getActiveAttempt() {
        return activeAttempt;
    }

    public synchronized String getPolicySummary() {
        return activePolicy == null ? "NONE" : activePolicy.summary();
    }

    public synchronized String getRequiredSafeEvidence() {
        return activePolicy == null ? "-" : activePolicy.safeEvidence;
    }

    public synchronized String getRequiredServiceEvidence() {
        return activePolicy == null ? "-" : activePolicy.serviceEvidence;
    }

    public synchronized String getLatestEvidence() {
        return latestEvidence;
    }

    public synchronized String getLocalSummary() {
        return "ROTARY=" + localDecision("ROTARY") + "; LID=" +
            localDecision("LID");
    }

    public synchronized String[] historySnapshot() {
        return history.toArray(new String[history.size()]);
    }

    public synchronized FaultSupervisorMetricsV2_1 metricsSnapshot() {
        return new FaultSupervisorMetricsV2_1(
            validEvents, rejectedMessages, duplicateMessages,
            automaticAttempts, verifiedRecoveries, resourceWaits,
            manualEscalations, recoveryFailures, 0
        );
    }

    public synchronized void reset() {
        priorEvents.clear();
        priorAcks.clear();
        priorResults.clear();
        localActiveEvents.clear();
        localDecisions.clear();
        localRetryCounts.clear();
        history.clear();
        activeEpoch = null;
        latestStateVersion = -1;
        validEvents = 0;
        rejectedMessages = 0;
        duplicateMessages = 0;
        automaticAttempts = 0;
        verifiedRecoveries = 0;
        resourceWaits = 0;
        manualEscalations = 0;
        recoveryFailures = 0;
        traceSequence = 0;
        clearActiveRecovery();
        clearOutputs();
    }

    private void issueRecoveryRequest() {
        if (activePolicy == null || activePolicy.maxAttempts != 1 ||
            activeAttempt >= activePolicy.maxAttempts) {
            failRecovery("ATTEMPT_LIMIT_REACHED");
            return;
        }
        activeAttempt++;
        String request = FaultProtocolV2_1.recoveryRequest(
            activeEvent,
            activePolicy.action,
            activeAttempt
        );
        if ("TRANSFER".equals(activeEvent.subsystem)) {
            pendingRecoveryRequest = request;
        }
        automaticAttempts++;
        state = State.WAITING_ACK;
        decision = activePolicy.action + "_ATTEMPT_" + activeAttempt;
        record("REQUEST " + request);
    }

    private boolean matchesActive(String eventId, String epoch, int attempt) {
        return activeEvent != null && activeEvent.eventId.equals(eventId) &&
            activeEvent.sourceEpoch.equals(epoch) &&
            attempt == activeAttempt && attempt > 0;
    }

    private String eventKey(String epoch, String eventId) {
        return epoch + "|" + eventId;
    }

    private boolean hasActiveRecovery() {
        return activeEvent != null && state != State.IDLE &&
            state != State.FAILED;
    }

    private boolean sameLocalEvent(String subsystem, String eventId) {
        return eventId != null && eventId.equals(
            localActiveEvents.get(subsystem)
        );
    }

    private boolean matchesLocalEvent(String subsystem, String eventId) {
        return sameLocalEvent(subsystem, eventId);
    }

    private String localDecision(String subsystem) {
        String value = localDecisions.get(subsystem);
        return value == null ? "READY" : value;
    }

    private boolean containsAllEvidence(String actual, String required) {
        String[] requiredTokens = required.split("\\+", -1);
        for (String token : requiredTokens) {
            if (!containsEvidence(actual, token)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsEvidence(String evidence, String token) {
        String[] values = evidence.split("\\+", -1);
        for (String value : values) {
            if (token.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private void reject(String reason) {
        rejectedMessages++;
        record("REJECTED " + reason);
    }

    private void failRecovery(String reason) {
        state = State.LOCKED_OUT;
        decision = reason;
        pendingRecoveryRequest = null;
        recoveryFailures++;
        queueRecoveryFailed(reason);
        record("RECOVERY_FAILED " + reason);
    }

    private void queueRecoveryFailed(String reason) {
        if (activeEvent == null) {
            return;
        }
        pendingRecoveryFailed = recoveryFailed(activeEvent, reason);
    }

    private String recoveryFailed(
        FaultProtocolV2_1.FaultEvent event,
        String reason
    ) {
        return "V2|" + event.eventId + "|" + event.sourceEpoch +
            "|RECOVERY_FAILED|" + safeToken(reason) + "|" +
            event.stateVersion;
    }

    private String safeStopRequest(FaultProtocolV2_1.FaultEvent event) {
        return "V2|" + event.eventId + "|" + event.sourceEpoch +
            "|SAFE_STOP|" + event.stateVersion;
    }

    private String recoveryReady(long stateVersion) {
        return "V2|" + activeEvent.eventId + "|" +
            activeEvent.sourceEpoch + "|RECOVERY_READY|" + stateVersion;
    }

    private String[] coordinationFields(String payload, int expected) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != expected || !"V2".equals(fields[0])) {
            throw new IllegalArgumentException("invalid V2 coordination payload");
        }
        for (int index = 1; index < fields.length; index++) {
            if (fields[index].isEmpty() ||
                !fields[index].equals(fields[index].trim())) {
                throw new IllegalArgumentException("empty coordination field");
            }
        }
        return fields;
    }

    private long unsignedLong(String value) {
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("version must be unsigned");
        }
        return Long.parseLong(value);
    }

    private String safeToken(String value) {
        return value.replace('|', '_').replace(' ', '_');
    }

    private void clearActiveRecovery() {
        activeEvent = null;
        activePolicy = null;
        activeAttempt = 0;
        manualEvidenceRecorded = false;
        state = State.IDLE;
        decision = "IDLE";
        latestEvidence = "NONE";
        pendingRecoveryRequest = null;
    }

    private void clearOutputs() {
        pendingFaultAlert = null;
        pendingSafeStopRequest = null;
        pendingRecoveryReady = null;
        pendingRecoveryFailed = null;
    }

    private void record(String entry) {
        traceSequence++;
        history.add(String.format("%04d %s", traceSequence, entry));
        if (history.size() > 300) {
            history.remove(0);
        }
    }
}
