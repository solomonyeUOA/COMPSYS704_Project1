/**
 * Validated M4 V1.2 bottle context used unchanged at the M2 Loader and
 * Unloader boundaries.
 */
public final class M2BottleContextV1 {
    private final String bottleId;
    private final String sizeCode;
    private final int capacityMl;
    private final String geometryProfileId;
    private final String packagingProfileId;

    public M2BottleContextV1(
        String bottleId,
        String sizeCode,
        int capacityMl,
        String geometryProfileId,
        String packagingProfileId
    ) {
        validateToken(bottleId, "bottleId");
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

    public static M2BottleContextV1 parse(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException("context payload is required");
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 5 ||
            !fields[2].matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("invalid bottle context payload");
        }
        return new M2BottleContextV1(
            fields[0],
            fields[1],
            Integer.parseInt(fields[2]),
            fields[3],
            fields[4]
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

    public static void validateToken(String value, String name) {
        if (value == null || value.isEmpty() ||
            !value.equals(value.trim()) || value.indexOf('|') >= 0 ||
            value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid " + name);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                throw new IllegalArgumentException(
                    name + " must be printable ASCII"
                );
            }
        }
    }

    public String getBottleId() {
        return bottleId;
    }

    public String getSizeCode() {
        return sizeCode;
    }

    public int getCapacityMl() {
        return capacityMl;
    }

    public String getGeometryProfileId() {
        return geometryProfileId;
    }

    public String getPackagingProfileId() {
        return packagingProfileId;
    }

    public String encode() {
        return bottleId + "|" + sizeCode + "|" + capacityMl + "|" +
            geometryProfileId + "|" + packagingProfileId;
    }

    public String encodeDetails() {
        return sizeCode + "," + capacityMl + "," + geometryProfileId +
            "," + packagingProfileId;
    }
}
