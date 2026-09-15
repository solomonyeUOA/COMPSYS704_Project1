import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Correlates the frozen four-message protocol. It emits only an abstract
 * LOCAL_RECOVERY_INTENT; the Conveyor Controller retains actuator authority.
 */
public final class M2TransferFaultAdapterModelV2_1 {
    private static final long FAULT_EVENT_RETRY_MILLIS = 500L;
    private final Map<String, String> priorRequests =
        new LinkedHashMap<String, String>();
    private M2TransferFaultProtocolV2_1.FaultEvent activeEvent;
    private String pendingFaultEvent;
    private String pendingAck;
    private String pendingIntent;
    private String pendingResult;
    private long nextFaultEventMillis;
    private final Map<String, String> completedEvents = new LinkedHashMap<String, String>();

    public boolean onLocalFault(String payload) {
        M2TransferFaultProtocolV2_1.FaultEvent event;
        try {
            event = M2TransferFaultProtocolV2_1.parseFaultEvent(payload);
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
        String completed = completedEvents.get(event.sourceEpoch + "|" + event.eventId);
        if (completed != null) return completed.equals(payload);
        if (activeEvent != null) {
            return activeEvent.raw.equals(payload);
        }
        activeEvent = event;
        pendingFaultEvent = payload;
        nextFaultEventMillis = 0L;
        return true;
    }

    public boolean onRecoveryRequest(String payload) {
        M2TransferFaultProtocolV2_1.RecoveryRequest request;
        try {
            request = M2TransferFaultProtocolV2_1.parseRecoveryRequest(
                payload
            );
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
        String key = request.sourceEpoch + "|" + request.eventId + "|" +
            request.attempt;
        String priorAck = priorRequests.get(key);
        if (priorAck != null) {
            pendingAck = priorAck;
            return true;
        }
        boolean matches = activeEvent != null &&
            activeEvent.eventId.equals(request.eventId) &&
            activeEvent.sourceEpoch.equals(request.sourceEpoch) &&
            activeEvent.stateVersion == request.expectedStateVersion &&
            "ARRIVAL_TIMEOUT".equals(activeEvent.faultCode) &&
            "RETRY_TRANSFER".equals(request.action) &&
            request.attempt == 1;
        String ack;
        if (matches) {
            ack = "V2|" + request.eventId + "|" + request.sourceEpoch +
                "|1|ACCEPTED|route_clear|" +
                request.expectedStateVersion;
            pendingIntent = payload;
        }
        else {
            long version = activeEvent == null ? 0 :
                activeEvent.stateVersion;
            ack = "V2|" + request.eventId + "|" + request.sourceEpoch +
                "|" + request.attempt +
                "|REJECTED|stale_or_unsafe|" + version;
        }
        priorRequests.put(key, ack);
        pendingAck = ack;
        return matches;
    }

    public boolean onLocalRecoveryEvidence(String payload) {
        try {
            M2TransferFaultProtocolV2_1.validateRecoveryResult(payload);
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
        if (activeEvent == null) {
            return false;
        }
        String[] fields = payload.split("\\|", -1);
        if (!activeEvent.eventId.equals(fields[1]) ||
            !activeEvent.sourceEpoch.equals(fields[2]) ||
            !"1".equals(fields[3])) {
            return false;
        }
        pendingResult = payload;
        completedEvents.put(activeEvent.sourceEpoch + "|" + activeEvent.eventId, activeEvent.raw);
        activeEvent = null;
        pendingFaultEvent = null;
        return true;
    }

    public boolean matchesActive(
        String eventId,
        String sourceEpoch,
        String faultCode,
        long stateVersion
    ) {
        return activeEvent != null &&
            activeEvent.eventId.equals(eventId) &&
            activeEvent.sourceEpoch.equals(sourceEpoch) &&
            activeEvent.faultCode.equals(faultCode) &&
            activeEvent.stateVersion == stateVersion;
    }

    public String takeFaultEvent() {
        return takeFaultEvent(System.currentTimeMillis());
    }

    String takeFaultEvent(long nowMillis) {
        if (activeEvent == null || nowMillis < nextFaultEventMillis) {
            return null;
        }
        String result = pendingFaultEvent == null ?
            activeEvent.raw : pendingFaultEvent;
        pendingFaultEvent = null;
        nextFaultEventMillis = nowMillis + FAULT_EVENT_RETRY_MILLIS;
        return result;
    }

    public String takeAck() {
        String result = pendingAck;
        pendingAck = null;
        return result;
    }

    public String takeIntent() {
        String result = pendingIntent;
        pendingIntent = null;
        return result;
    }

    public String takeResult() {
        String result = pendingResult;
        pendingResult = null;
        return result;
    }
}
