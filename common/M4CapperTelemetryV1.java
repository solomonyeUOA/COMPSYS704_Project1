/** Validated read-only M4 Capper telemetry shared with the M1 visualisation. */
public final class M4CapperTelemetryV1 {
    private static final String VERSION = "V1";
    private static final String IDLE_VALUE = "-";

    private final String bottleId;
    private final String sizeCode;
    private final String geometryProfile;
    private final String stage;
    private final int status;

    private M4CapperTelemetryV1(
        String bottle,
        String size,
        String geometry,
        String capperStage,
        int capperStatus
    ) {
        bottleId = bottle;
        sizeCode = size;
        geometryProfile = geometry;
        stage = capperStage;
        status = capperStatus;
    }

    public static M4CapperTelemetryV1 of(
        String bottleId,
        String sizeCode,
        String geometryProfile,
        String stage,
        int status
    ) {
        validateField(bottleId, "bottleId");
        boolean idleProfile = IDLE_VALUE.equals(sizeCode) &&
            IDLE_VALUE.equals(geometryProfile);
        boolean smallProfile = "S".equals(sizeCode) &&
            "GEOM_S".equals(geometryProfile);
        boolean largeProfile = "L".equals(sizeCode) &&
            "GEOM_L".equals(geometryProfile);
        if (!idleProfile && !smallProfile && !largeProfile) {
            throw new IllegalArgumentException("invalid size/geometry profile");
        }
        if (IDLE_VALUE.equals(bottleId) != idleProfile) {
            throw new IllegalArgumentException(
                "idle bottle and geometry fields must agree"
            );
        }
        if (!isStage(stage) || status < 0 || status > 4) {
            throw new IllegalArgumentException("invalid Capper stage/status");
        }
        return new M4CapperTelemetryV1(
            bottleId, sizeCode, geometryProfile, stage, status
        );
    }

    public static M4CapperTelemetryV1 parse(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Capper telemetry is required");
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 6 || !VERSION.equals(fields[0])) {
            throw new IllegalArgumentException("invalid Capper telemetry format");
        }
        final int parsedStatus;
        try {
            parsedStatus = Integer.parseInt(fields[5]);
        }
        catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid Capper status");
        }
        return of(fields[1], fields[2], fields[3], fields[4], parsedStatus);
    }

    public String encode() {
        return VERSION + "|" + bottleId + "|" + sizeCode + "|" +
            geometryProfile + "|" + stage + "|" + status;
    }

    public String getBottleId() { return bottleId; }

    public String getSizeCode() { return sizeCode; }

    public String getGeometryProfile() { return geometryProfile; }

    public String getStage() { return stage; }

    public int getStatus() { return status; }

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
            "CLAMPING".equals(value) || "LOWERING".equals(value) ||
            "GRIPPING".equals(value) || "TWISTING".equals(value) ||
            "RELEASING".equals(value) || "RETURNING".equals(value) ||
            "RAISING".equals(value) || "UNCLAMPING".equals(value) ||
            "DONE".equals(value) || "FAULT".equals(value);
    }
}
