import java.util.HashMap;
import java.util.Map;

/** Single logical registry for validated S/L bottle contexts. */
public final class BottleContextRegistryModelV1 {
    private final Map<String, M4BottleContextV1> contexts =
        new HashMap<String, M4BottleContextV1>();
    private int status = M4StatusV1.READY;
    private String faultReason = "-";

    /**
     * Returns a new full context, or null for an identical duplicate.
     * Invalid or conflicting registrations enter FAULT and throw.
     */
    public String acceptRecognition(String payload) {
        try {
            M4BottleContextV1 candidate =
                M4BottleContextV1.fromRecognition(payload);
            M4BottleContextV1 existing =
                contexts.get(candidate.getBottleId());
            if (existing != null) {
                if (existing.equals(candidate)) {
                    return null;
                }
                fail("CONFLICTING_CONTEXT");
                throw new IllegalArgumentException(
                    "conflicting context for existing bottleId"
                );
            }
            contexts.put(candidate.getBottleId(), candidate);
            status = M4StatusV1.DONE;
            faultReason = "-";
            return candidate.encode();
        }
        catch (IllegalArgumentException exception) {
            if (status != M4StatusV1.FAULT) {
                fail("INVALID_RECOGNITION");
            }
            throw exception;
        }
    }

    public M4BottleContextV1 get(String bottleId) {
        return contexts.get(bottleId);
    }

    public int size() {
        return contexts.size();
    }

    public int getStatus() {
        return status;
    }

    public String getFaultReason() {
        return faultReason;
    }

    public void resetFault() {
        if (status == M4StatusV1.FAULT) {
            status = M4StatusV1.READY;
            faultReason = "-";
        }
    }

    private void fail(String reason) {
        status = M4StatusV1.FAULT;
        faultReason = reason;
    }
}
