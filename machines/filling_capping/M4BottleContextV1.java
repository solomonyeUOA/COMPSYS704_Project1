/** Validated context used by all M4 two-size operations. */
public final class M4BottleContextV1 {
    public static final String SMALL = "S";
    public static final String LARGE = "L";
    public static final String GEOMETRY_SMALL = "GEOM_S";
    public static final String GEOMETRY_LARGE = "GEOM_L";
    public static final String PACKAGING_SMALL = "PACK_S";
    public static final String PACKAGING_LARGE = "PACK_L";

    private final String bottleId;
    private final String batchId;
    private final String sizeCode;
    private final int capacityMl;
    private final String geometryProfileId;
    private final String packagingProfileId;

    public M4BottleContextV1(
        String bottleId,
        String sizeCode,
        int capacityMl,
        String geometryProfileId,
        String packagingProfileId
    ) {
        this(
            bottleId,
            null,
            sizeCode,
            capacityMl,
            geometryProfileId,
            packagingProfileId
        );
    }

    private M4BottleContextV1(
        String bottleId,
        String batchId,
        String sizeCode,
        int capacityMl,
        String geometryProfileId,
        String packagingProfileId
    ) {
        M4ProtocolV1.validateBottleId(bottleId);
        if (batchId != null) {
            M4ProtocolV1.validateBottleId(batchId);
        }
        boolean small = SMALL.equals(sizeCode) && capacityMl == 200 &&
            GEOMETRY_SMALL.equals(geometryProfileId) &&
            PACKAGING_SMALL.equals(packagingProfileId);
        boolean large = LARGE.equals(sizeCode) && capacityMl == 500 &&
            GEOMETRY_LARGE.equals(geometryProfileId) &&
            PACKAGING_LARGE.equals(packagingProfileId);
        if (!small && !large) {
            throw new IllegalArgumentException("unsupported bottle context");
        }
        this.bottleId = bottleId;
        this.batchId = batchId;
        this.sizeCode = sizeCode;
        this.capacityMl = capacityMl;
        this.geometryProfileId = geometryProfileId;
        this.packagingProfileId = packagingProfileId;
    }

    public static M4BottleContextV1 fromRecognition(String payload) {
        String[] fields = M4ProtocolV1.fields(payload, 4);
        int capacity = M4ProtocolV1.unsignedInteger(fields[3], "capacityMl");
        if (SMALL.equals(fields[2]) && capacity == 200) {
            return new M4BottleContextV1(
                fields[0], fields[1], SMALL, 200,
                GEOMETRY_SMALL, PACKAGING_SMALL
            );
        }
        if (LARGE.equals(fields[2]) && capacity == 500) {
            return new M4BottleContextV1(
                fields[0], fields[1], LARGE, 500,
                GEOMETRY_LARGE, PACKAGING_LARGE
            );
        }
        throw new IllegalArgumentException("size/capacity mismatch");
    }

    public static M4BottleContextV1 parse(String payload) {
        String[] fields = M4ProtocolV1.fields(payload, 5);
        return new M4BottleContextV1(
            fields[0],
            fields[1],
            M4ProtocolV1.unsignedInteger(fields[2], "capacityMl"),
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

    public String getBottleId() {
        return bottleId;
    }

    /** Present only on the registry's internal recognised-bottle record. */
    public String getBatchId() {
        return batchId;
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

    public M4GeometryProfileV1 getGeometryProfile() {
        return M4GeometryProfileV1.forId(geometryProfileId);
    }

    public String getPackagingProfileId() {
        return packagingProfileId;
    }

    /** Calculate the nearest whole-mL target from tenths-of-percent units. */
    public int targetForRatio(int ratioUnits) {
        return RecipeRatioV2.targetMl(capacityMl, ratioUnits);
    }

    public String encode() {
        return bottleId + "|" + sizeCode + "|" + capacityMl + "|" +
            geometryProfileId + "|" + packagingProfileId;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof M4BottleContextV1)) {
            return false;
        }
        M4BottleContextV1 candidate = (M4BottleContextV1) other;
        return encode().equals(candidate.encode()) &&
            (batchId == null ? candidate.batchId == null :
                batchId.equals(candidate.batchId));
    }

    @Override
    public int hashCode() {
        return 31 * encode().hashCode() +
            (batchId == null ? 0 : batchId.hashCode());
    }
}
