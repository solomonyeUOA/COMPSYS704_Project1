/** Conceptual two-size recognition result used by the simulated Plant. */
public final class RecognitionPlantModelV1 {
    private RecognitionPlantModelV1() {
    }

    /** Accepts bottleId|S or bottleId|L and returns the three-field result. */
    public static String recognise(String request) {
        try {
            String[] fields = M4ProtocolV1.fields(request, 2);
            M4ProtocolV1.validateBottleId(fields[0]);
            if (M4BottleContextV1.SMALL.equals(fields[1])) {
                return fields[0] + "|S|200";
            }
            if (M4BottleContextV1.LARGE.equals(fields[1])) {
                return fields[0] + "|L|500";
            }
            return null;
        }
        catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
