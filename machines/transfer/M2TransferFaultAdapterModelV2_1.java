import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Correlates the frozen four-message protocol. It emits only an abstract
 * LOCAL_RECOVERY_INTENT; the Conveyor Controller retains actuator authority.
 */
public final class M2TransferFaultAdapterModelV2_1 {
    private final Map<String, String> priorRequests =
        new LinkedHashMap<String, String>();
    private M2TransferFaultProtocolV2_1.FaultEvent activeEvent;
    private String pendingFaultEvent;
    private String pendingAck;
    private String pendingIntent;
    private String pendingResult;

    public boolean onLocalFault(String payload) {
        M2TransferFaultProtocolV2_1.FaultEvent event;
        try {
            event = M2TransferFaultProtocolV2_1.parseFaultEvent(payload);
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
        if (activeEvent != null) {
            return activeEvent.raw.equals(payload);
        }
        activeEvent = event;
        pendingFaultEvent = payload;
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
        activeEvent = null;
        return true;
    }

    public String takeFaultEvent() {
        String result = pendingFaultEvent;
        pendingFaultEvent = null;
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
