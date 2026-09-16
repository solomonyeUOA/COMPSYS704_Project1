/** Validated read-only M4 Filler telemetry shared with the M1 visualisation. */
public final class M4FillerTelemetryV1 {
    private static final String VERSION = "V1";
    private static final String IDLE_VALUE = "-";

    private final String liquid;
    private final String bottleId;
    private final String sizeCode;
    private final String geometryProfile;
    private final String stage;
    private final int status;
    private final int targetMl;
    private final int measuredMl;

    private M4FillerTelemetryV1(
        String fillerLiquid,
        String bottle,
        String size,
        String geometry,
        String fillerStage,
        int fillerStatus,
        int target,
        int measured
    ) {
        liquid = fillerLiquid;
        bottleId = bottle;
        sizeCode = size;
        geometryProfile = geometry;
        stage = fillerStage;
        status = fillerStatus;
        targetMl = target;
        measuredMl = measured;
    }

    public static M4FillerTelemetryV1 of(
        String liquid,
        String bottleId,
        String sizeCode,
        String geometryProfile,
        String stage,
        int status,
        int targetMl,
        int measuredMl
    ) {
        if (!"A".equals(liquid) && !"B".equals(liquid)) {
            throw new IllegalArgumentException("invalid filler liquid");
        }
        validateField(bottleId, "bottleId");
        boolean idleProfile = IDLE_VALUE.equals(sizeCode) &&
            IDLE_VALUE.equals(geometryProfile);
        boolean smallProfile = "S".equals(sizeCode) &&
            "GEOM_S".equals(geometryProfile);
        boolean largeProfile = "L".equals(sizeCode) &&
            "GEOM_L".equals(geometryProfile);
        if ((!idleProfile && !smallProfile && !largeProfile) ||
            (IDLE_VALUE.equals(bottleId) != idleProfile)) {
            throw new IllegalArgumentException("invalid filler bottle profile");
        }
        if (!isStage(stage) || status < 0 || status > 4 ||
            targetMl < 0 || measuredMl < 0) {
            throw new IllegalArgumentException("invalid filler state");
        }
        return new M4FillerTelemetryV1(
            liquid, bottleId, sizeCode, geometryProfile, stage, status,
            targetMl, measuredMl
        );
    }

    public static M4FillerTelemetryV1 parse(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException("filler telemetry is required");
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 9 || !VERSION.equals(fields[0])) {
            throw new IllegalArgumentException("invalid filler telemetry format");
        }
        try {
            return of(fields[1], fields[2], fields[3], fields[4], fields[5],
                Integer.parseInt(fields[6]), Integer.parseInt(fields[7]),
                Integer.parseInt(fields[8]));
        }
        catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid filler numeric state");
        }
    }

    public String encode() {
        return VERSION + "|" + liquid + "|" + bottleId + "|" + sizeCode +
            "|" + geometryProfile + "|" + stage + "|" + status + "|" +
            targetMl + "|" + measuredMl;
    }

    public String getLiquid() { return liquid; }
    public String getBottleId() { return bottleId; }
    public String getSizeCode() { return sizeCode; }
    public String getGeometryProfile() { return geometryProfile; }
    public String getStage() { return stage; }
    public int getStatus() { return status; }
    public int getTargetMl() { return targetMl; }
    public int getMeasuredMl() { return measuredMl; }
    public boolean hasBottle() { return !IDLE_VALUE.equals(bottleId); }

    private static void validateField(String value, String name) {
        if (value == null || value.length() == 0 ||
            value.indexOf('|') >= 0 || value.indexOf('\r') >= 0 ||
            value.indexOf('\n') >= 0 || !value.equals(value.trim())) {
            throw new IllegalArgumentException("invalid " + name);
        }
    }

    private static boolean isStage(String value) {
        return "WAITING".equals(value) || "POSITIONING".equals(value) ||
            "DOSING".equals(value) || "REFILLING".equals(value) ||
            "SAFE_WAIT".equals(value) || "DONE".equals(value) ||
            "FAULT".equals(value);
    }
}
