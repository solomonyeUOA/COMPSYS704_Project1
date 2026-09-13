import java.util.HashMap;
import java.util.Map;

/** M4-owned view of the product batches announced by the Coordinator. */
public final class M4BatchRegistryModelV1 {
    private static final class Contract {
        private final String batchId;
        private final int quantity;
        private final String sizeCode;
        private int recognised;

        private Contract(String id, int count, String size) {
            batchId = id;
            quantity = count;
            sizeCode = size;
        }

        private String encode() {
            return batchId + "|" + quantity + "|" + sizeCode;
        }
    }

    private final Map<String, Contract> contracts =
        new HashMap<String, Contract>();
    private final Map<String, String> bottleBatches =
        new HashMap<String, String>();
    private String activeBatchId;

    /** Returns the canonical contract for accepted and duplicate deliveries. */
    public String acceptBatchStart(String payload) {
        Contract candidate;
        try {
            candidate = parse(payload);
        }
        catch (IllegalArgumentException invalid) {
            return null;
        }
        if (!M4ResetFenceV1.acceptBatch(candidate.batchId)) {
            return null;
        }
        Contract existing = contracts.get(candidate.batchId);
        if (existing != null) {
            return existing.encode().equals(candidate.encode()) ?
                existing.encode() : null;
        }
        contracts.put(candidate.batchId, candidate);
        activeBatchId = candidate.batchId;
        return candidate.encode();
    }

    /**
     * Correlates the M4-local sensor payload bottleId|sizeCode with the
     * current Coordinator-owned batch. The sensor never invents a batch ID.
     */
    public String recognise(String request) {
        Contract active = contracts.get(activeBatchId);
        if (active == null) { return null; }
        return RecognitionPlantModelV1.recognise(
            request,
            active.batchId,
            active.sizeCode
        );
    }

    public void validateRecognition(M4BottleContextV1 context) {
        String batchId = context.getBatchId();
        Contract contract = contracts.get(batchId);
        if (contract == null || !contract.sizeCode.equals(
            context.getSizeCode()
        )) {
            throw new IllegalArgumentException(
                "recognition does not match a registered batch"
            );
        }
        String existingBatch = bottleBatches.get(context.getBottleId());
        if (existingBatch != null && !existingBatch.equals(batchId)) {
            throw new IllegalArgumentException(
                "bottleId already belongs to another batch"
            );
        }
        if (existingBatch == null && contract.recognised >= contract.quantity) {
            throw new IllegalArgumentException(
                "recognition exceeds declared batch quantity"
            );
        }
    }

    public void recordRecognition(M4BottleContextV1 context) {
        if (bottleBatches.containsKey(context.getBottleId())) { return; }
        Contract contract = contracts.get(context.getBatchId());
        if (contract == null) {
            throw new IllegalStateException("batch disappeared during recognition");
        }
        bottleBatches.put(context.getBottleId(), context.getBatchId());
        contract.recognised++;
    }

    /** Validates a batch-end retry before it reaches Sort/Pack. */
    public boolean acceptsBatchEnd(String payload) {
        Contract candidate;
        try {
            candidate = parse(payload);
        }
        catch (IllegalArgumentException invalid) {
            return false;
        }
        if (!M4ResetFenceV1.acceptBatch(candidate.batchId)) {
            return false;
        }
        Contract existing = contracts.get(candidate.batchId);
        return existing != null && existing.encode().equals(candidate.encode());
    }

    public String activeBatchId() {
        return activeBatchId;
    }

    private static Contract parse(String payload) {
        String[] fields = M4ProtocolV1.fields(payload, 3);
        M4ProtocolV1.validateBottleId(fields[0]);
        int quantity = M4ProtocolV1.unsignedInteger(fields[1], "quantity");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (!M4BottleContextV1.SMALL.equals(fields[2]) &&
            !M4BottleContextV1.LARGE.equals(fields[2])) {
            throw new IllegalArgumentException("sizeCode must be S or L");
        }
        return new Contract(fields[0], quantity, fields[2]);
    }
}
