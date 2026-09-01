/** Validated M4 V1.2 bottle context accepted at the optional M3 boundary. */
public final class BottleContextV1 {
    private final String bottleId;
    private final String sizeCode;
    private final int capacityMl;
    private final String geometryProfileId;
    private final String packagingProfileId;

    public BottleContextV1(
        String bottleId,
        String sizeCode,
        int capacityMl,
        String geometryProfileId,
        String packagingProfileId
    ) {
        validateBottleId(bottleId);
        boolean small = "S".equals(sizeCode) && capacityMl == 200 &&
            "GEOM_S".equals(geometryProfileId) &&
            "PACK_S".equals(packagingProfileId);
        boolean large = "L".equals(sizeCode) && capacityMl == 500 &&
            "GEOM_L".equals(geometryProfileId) &&
            "PACK_L".equals(packagingProfileId);
        if (!small && !large) {
            throw new IllegalArgumentException("unsupported bottle context");
        }
        this.bottleId = bottleId;
        this.sizeCode = sizeCode;
        this.capacityMl = capacityMl;
        this.geometryProfileId = geometryProfileId;
        this.packagingProfileId = packagingProfileId;
    }

    public static BottleContextV1 parse(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException("context payload is required");
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 5 || !fields[2].matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("invalid bottle context payload");
        }
        return new BottleContextV1(
            fields[0], fields[1], Integer.parseInt(fields[2]),
            fields[3], fields[4]
        );
    }

    public static boolean isValid(String payload) {
        try {
            parse(payload);
            return true;
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static void validateBottleId(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim()) ||
            value.indexOf('|') >= 0 || value.indexOf('\r') >= 0 ||
            value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid bottleId");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < 0x21 || character > 0x7e) {
                throw new IllegalArgumentException("bottleId must be printable ASCII");
            }
        }
    }

    public String getBottleId() {
        return bottleId;
    }

    public String encode() {
        return bottleId + "|" + sizeCode + "|" + capacityMl + "|" +
            geometryProfileId + "|" + packagingProfileId;
    }
}
