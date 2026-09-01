import java.util.HashSet;
import java.util.Set;

/** Entry Conveyor state machine with evidence-gated P1 hand-off. */
public final class ConveyorControllerModelV1 {
    private final Set<String> completedBottleIds = new HashSet<String>();
    private final long arrivalTimeoutMillis;
    private int status = M2StatusV1.READY;
    private M2BottleContextV1 active;
    private boolean contextPending;
    private boolean motorEnabled;
    private boolean loadBottlePending;
    private long transferStartedAtMillis;
    private long stateVersion;
    private String faultCode;
    private String faultPayload;
    private String recoveryEventId;
    private String recoveryEpoch;
    private int recoveryAttempt;

    public ConveyorControllerModelV1() {
        this(2000L);
    }

    public ConveyorControllerModelV1(long arrivalTimeoutMillis) {
        if (arrivalTimeoutMillis < 0) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        this.arrivalTimeoutMillis = arrivalTimeoutMillis;
    }

    /** Status access is observational and never advances the Conveyor. */
    public int getStatus() {
        return status;
    }

    public long getStateVersion() {
        return stateVersion;
    }

    public boolean canAcceptBottle() {
        return active == null && status == M2StatusV1.READY;
    }

    public String getActiveBottleId() {
        return active == null ? null : active.getBottleId();
    }

    public boolean offerBottle(String contextPayload) {
        M2BottleContextV1 context;
        try {
            context = M2BottleContextV1.parse(contextPayload);
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
        if (!canAcceptBottle() ||
            completedBottleIds.contains(context.getBottleId())) {
            return false;
        }
        active = context;
        contextPending = true;
        stateVersion++;
        return true;
    }

    public String takeTransferContext() {
        if (!contextPending || active == null) {
            return null;
        }
        contextPending = false;
        return active.getBottleId();
    }

    public boolean startTransfer(long nowMillis) {
        if (active == null || status != M2StatusV1.READY) {
            return false;
        }
        motorEnabled = true;
        status = M2StatusV1.BUSY;
        transferStartedAtMillis = nowMillis;
        faultCode = null;
        stateVersion++;
        return true;
    }

    public boolean isMotorEnabled() {
        return motorEnabled;
    }

    /**
     * P1_FEEDBACK schema:
     * bottleId|p1Present|entryClear|motorStopped|rotaryAligned|p1Available
     */
    public boolean acceptP1Feedback(String payload) {
        if (payload == null || active == null ||
            status != M2StatusV1.BUSY) {
            return false;
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 6 ||
            !active.getBottleId().equals(fields[0])) {
            return false;
        }
        Boolean p1Present = parseBoolean(fields[1]);
        Boolean entryClear = parseBoolean(fields[2]);
        Boolean motorStopped = parseBoolean(fields[3]);
        Boolean rotaryAligned = parseBoolean(fields[4]);
        Boolean p1Available = parseBoolean(fields[5]);
        if (p1Present == null || entryClear == null ||
            motorStopped == null || rotaryAligned == null ||
            p1Available == null) {
            return false;
        }
        if (p1Present.booleanValue()) {
            motorEnabled = false;
        }
        boolean complete = p1Present.booleanValue() &&
            entryClear.booleanValue() && motorStopped.booleanValue() &&
            rotaryAligned.booleanValue() && p1Available.booleanValue();
        if (!complete) {
            return true;
        }
        status = M2StatusV1.DONE;
        loadBottlePending = true;
        faultCode = null;
        stateVersion++;
        return true;
    }

    public void tick(long nowMillis, String sourceEpoch) {
        if (status != M2StatusV1.BUSY || active == null ||
            arrivalTimeoutMillis == 0 ||
            nowMillis - transferStartedAtMillis < arrivalTimeoutMillis) {
            return;
        }
        motorEnabled = false;
        status = M2StatusV1.FAULT;
        faultCode = "ARRIVAL_TIMEOUT";
        stateVersion++;
        String eventId = "M2-TRANSFER-" + stateVersion;
        faultPayload = "V2|" + eventId + "|" + sourceEpoch +
            "|TRANSFER|ARRIVAL_TIMEOUT|WARNING|" +
            active.getBottleId() + "|" + stateVersion;
    }

    public String takeFaultPayload() {
        String result = faultPayload;
        faultPayload = null;
        return result;
    }

    public boolean acceptRecoveryRequest(String payload, long nowMillis) {
        String[] fields = payload == null ? new String[0] :
            payload.split("\\|", -1);
        if (fields.length != 6 || !"V2".equals(fields[0]) ||
            status != M2StatusV1.FAULT || active == null || motorEnabled ||
            !"RETRY_TRANSFER".equals(fields[3]) ||
            !"1".equals(fields[4]) ||
            !Long.toString(stateVersion).equals(fields[5])) {
            return false;
        }
        recoveryEventId = fields[1];
        recoveryEpoch = fields[2];
        recoveryAttempt = 1;
        status = M2StatusV1.READY;
        return startTransfer(nowMillis);
    }

    public String takeLoadBottle() {
        if (!loadBottlePending || active == null) {
            return null;
        }
        String result = active.getBottleId();
        completedBottleIds.add(result);
        loadBottlePending = false;
        active = null;
        motorEnabled = false;
        status = M2StatusV1.READY;
        return result;
    }

    public String takeRecoveryEvidence() {
        if (recoveryEventId == null || loadBottlePending || active != null) {
            return null;
        }
        String result = "V2|" + recoveryEventId + "|" + recoveryEpoch +
            "|" + recoveryAttempt +
            "|SUCCESS|motor_off+occupancy_consistent|arrival_confirmed|" +
            stateVersion;
        recoveryEventId = null;
        recoveryEpoch = null;
        recoveryAttempt = 0;
        return result;
    }

    public String getFaultCode() {
        return faultCode;
    }

    private static Boolean parseBoolean(String value) {
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
