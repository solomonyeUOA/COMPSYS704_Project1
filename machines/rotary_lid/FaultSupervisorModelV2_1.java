import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Policy and correlation model for the M3 individual-project supervisor. */
public final class FaultSupervisorModelV2_1 {
    public enum State {
        IDLE,
        WAITING_SAFE_STOP,
        WAITING_ACK,
        WAITING_RESULT,
        RECOVERY_READY,
        MANUAL_RECOVERY,
        FAILED
    }

    private final Map<String, String> priorRequests =
        new HashMap<String, String>();
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
    private State state = State.IDLE;
    private String pendingRequest;
    private String decision = "IDLE";

    public synchronized boolean onTransferFault(String payload) {
        FaultProtocolV2_1.FaultEvent event;
        try {
            event = FaultProtocolV2_1.parseFaultEvent(payload);
        }
        catch (IllegalArgumentException exception) {
            fail("INVALID_EVENT: " + exception.getMessage());
            return false;
        }
        if (!"TRANSFER".equals(event.subsystem)) {
            fail("WRONG_SUBSYSTEM " + event.subsystem);
            return false;
        }
        String eventKey = eventKey(event.sourceEpoch, event.eventId);
        if (priorEvents.containsKey(eventKey)) {
            boolean identical = priorEvents.get(eventKey).equals(payload);
            record((identical ? "DUPLICATE_EVENT " :
                "CONFLICTING_EVENT_ID ") + event.eventId);
            if (!identical) {
                fail("CONFLICTING_EVENT_ID " + event.eventId);
            }
            return identical;
        }
        if (activeEpoch != null && !activeEpoch.equals(event.sourceEpoch)) {
            record("NEW_EPOCH " + event.sourceEpoch + " invalidated " +
                activeEpoch);
            clearActiveRecovery();
            latestStateVersion = -1;
        }
        activeEpoch = event.sourceEpoch;
        if (hasActiveRecovery() && activeEvent != null &&
            !activeEvent.eventId.equals(event.eventId)) {
            boolean preempts = "CRITICAL".equals(event.severity) &&
                !"CRITICAL".equals(activeEvent.severity);
            if (!preempts) {
                record("CONCURRENT_EVENT_HELD " + event.eventId);
                return false;
            }
            record("CRITICAL_PREEMPT " + event.eventId + " replaced " +
                activeEvent.eventId);
            clearActiveRecovery();
        }
        if (event.stateVersion < latestStateVersion) {
            fail("STALE_STATE " + event.eventId);
            return false;
        }
        if (latestStateVersion >= 0 &&
            event.stateVersion > latestStateVersion + 1) {
            fail("STATE_SNAPSHOT_REQUIRED " + event.eventId);
            return false;
        }
        latestStateVersion = event.stateVersion;
        activeEvent = event;
        priorEvents.put(eventKey, payload);
        record("FAULT " + event.eventId + " " + event.faultCode);
        if ("ARRIVAL_TIMEOUT".equals(event.faultCode)) {
            state = State.WAITING_SAFE_STOP;
            decision = "WAITING_FOR_INDEPENDENT_SAFE_STOP";
        }
        else {
            state = State.MANUAL_RECOVERY;
            decision = "NO_BLIND_RETRY";
        }
        return true;
    }

    /** Called only by a future M1 parser after genuine independent evidence. */
    public synchronized boolean confirmSafeStop(
        String eventId,
        boolean independentEvidence
    ) {
        if (!independentEvidence || state != State.WAITING_SAFE_STOP ||
            activeEvent == null || !activeEvent.eventId.equals(eventId)) {
            return false;
        }
        pendingRequest = FaultProtocolV2_1.recoveryRequest(
            activeEvent,
            "RETRY_TRANSFER",
            1
        );
        priorRequests.put(eventKey(
            activeEvent.sourceEpoch,
            activeEvent.eventId
        ), pendingRequest);
        state = State.WAITING_ACK;
        decision = "RETRY_TRANSFER_ATTEMPT_1";
        record("REQUEST " + pendingRequest);
        return true;
    }

    public synchronized boolean onRecoveryAck(String payload) {
        FaultProtocolV2_1.RecoveryAck ack;
        try {
            ack = FaultProtocolV2_1.parseRecoveryAck(payload);
        }
        catch (IllegalArgumentException exception) {
            fail("INVALID_ACK: " + exception.getMessage());
            return false;
        }
        String key = eventKey(ack.sourceEpoch, ack.eventId) + "|" +
            ack.attempt;
        if (priorAcks.containsKey(key)) {
            record("DUPLICATE_ACK " + key);
            return priorAcks.get(key).equals(payload);
        }
        if (!matchesActive(ack.eventId, ack.sourceEpoch, ack.attempt) ||
            ack.acceptedStateVersion != activeEvent.stateVersion) {
            fail("STALE_OR_MISMATCHED_ACK " + key);
            return false;
        }
        priorAcks.put(key, payload);
        if (!"ACCEPTED".equals(ack.ack)) {
            fail("RECOVERY_REJECTED " + ack.reason);
            return false;
        }
        state = State.WAITING_RESULT;
        decision = "RECOVERY_IN_PROGRESS";
        record("ACK " + key);
        return true;
    }

    public synchronized boolean onRecoveryResult(String payload) {
        FaultProtocolV2_1.RecoveryResult result;
        try {
            result = FaultProtocolV2_1.parseRecoveryResult(payload);
        }
        catch (IllegalArgumentException exception) {
            fail("INVALID_RESULT: " + exception.getMessage());
            return false;
        }
        String key = eventKey(result.sourceEpoch, result.eventId) + "|" +
            result.attempt;
        if (priorResults.containsKey(key)) {
            record("DUPLICATE_RESULT " + key);
            return priorResults.get(key).equals(payload);
        }
        if (state != State.WAITING_RESULT ||
            !matchesActive(result.eventId, result.sourceEpoch, result.attempt)) {
            fail("STALE_OR_MISMATCHED_RESULT " + key);
            return false;
        }
        priorResults.put(key, payload);
        boolean safe = containsEvidence(result.safeEvidence, "motor_off") &&
            containsEvidence(result.safeEvidence, "occupancy_consistent");
        boolean service = containsEvidence(
            result.serviceEvidence,
            "arrival_confirmed"
        );
        if (!"SUCCESS".equals(result.outcome) || !safe || !service ||
            result.resultingStateVersion <= activeEvent.stateVersion) {
            fail("INVALID_RECOVERY_EVIDENCE " + key);
            return false;
        }
        latestStateVersion = result.resultingStateVersion;
        state = State.RECOVERY_READY;
        decision = "VERIFIED_READY_AWAIT_M1";
        record("RECOVERY_READY " + key);
        return true;
    }

    public synchronized void reportAckTimeout() {
        if (state == State.WAITING_ACK) {
            fail("ACK_TIMEOUT_NO_RESEND");
        }
    }

    public synchronized void reportResultTimeout() {
        if (state == State.WAITING_RESULT) {
            fail("RESULT_TIMEOUT");
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
        localDecisions.put("ROTARY", "RESET_AUTHORIZED_AFTER_RECONCILIATION");
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
            localDecisions.put("LID", "RESET_AUTHORIZED_AFTER_EVIDENCE");
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

    public synchronized String getLocalSummary() {
        return "ROTARY=" + localDecision("ROTARY") + "; LID=" +
            localDecision("LID");
    }

    public synchronized String takeRecoveryRequest() {
        String result = pendingRequest;
        pendingRequest = null;
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

    public synchronized String[] historySnapshot() {
        return history.toArray(new String[history.size()]);
    }

    public synchronized void reset() {
        priorRequests.clear();
        priorEvents.clear();
        priorAcks.clear();
        priorResults.clear();
        localActiveEvents.clear();
        localDecisions.clear();
        localRetryCounts.clear();
        history.clear();
        activeEpoch = null;
        latestStateVersion = -1;
        activeEvent = null;
        state = State.IDLE;
        pendingRequest = null;
        decision = "IDLE";
    }

    private boolean matchesActive(String eventId, String epoch, int attempt) {
        return activeEvent != null && activeEvent.eventId.equals(eventId) &&
            activeEvent.sourceEpoch.equals(epoch) && attempt == 1;
    }

    private String eventKey(String epoch, String eventId) {
        return epoch + "|" + eventId;
    }

    private boolean hasActiveRecovery() {
        return state == State.WAITING_SAFE_STOP ||
            state == State.WAITING_ACK || state == State.WAITING_RESULT ||
            state == State.RECOVERY_READY || state == State.MANUAL_RECOVERY;
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

    private boolean containsEvidence(String evidence, String token) {
        String[] values = evidence.split("\\+", -1);
        for (String value : values) {
            if (token.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private void fail(String reason) {
        state = State.FAILED;
        decision = reason;
        pendingRequest = null;
        record("FAILED " + reason);
    }

    private void clearActiveRecovery() {
        activeEvent = null;
        pendingRequest = null;
        state = State.IDLE;
        decision = "IDLE";
    }

    private void record(String entry) {
        history.add(System.currentTimeMillis() + " " + entry);
        if (history.size() > 200) {
            history.remove(0);
        }
    }
}
