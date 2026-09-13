/** Conceptual two-size recognition result used by the M4 Plant. */
public final class RecognitionPlantModelV1 {
    private RecognitionPlantModelV1() {
    }

    /**
     * Accepts the sensor-owned bottleId|size payload. M4 supplies the batch
     * association that it previously received from the Coordinator.
     */
    public static String recognise(
        String request,
        String batchId,
        String expectedSizeCode
    ) {
        try {
            String[] fields = M4ProtocolV1.fields(request, 2);
            M4ProtocolV1.validateBottleId(fields[0]);
            M4ProtocolV1.validateBottleId(batchId);
            if (!fields[1].equals(expectedSizeCode)) { return null; }
            if (M4BottleContextV1.SMALL.equals(fields[1])) {
                return fields[0] + "|" + batchId + "|S|200";
            }
            if (M4BottleContextV1.LARGE.equals(fields[1])) {
                return fields[0] + "|" + batchId + "|L|500";
            }
            return null;
        }
        catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
