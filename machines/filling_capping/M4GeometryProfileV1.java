/**
 * Calibrated symbolic actuator targets for the two supported M4 bottle sizes.
 *
 * The simulation has no millimetre-valued actuator interface, so these names
 * represent stored machine calibration points.  Controllers still exchange
 * the frozen GEOM_S / GEOM_L profile IDs with the Plants.
 */
public final class M4GeometryProfileV1 {
    public static final String NOZZLE_Z_SMALL = "NOZZLE_Z_S";
    public static final String NOZZLE_Z_LARGE = "NOZZLE_Z_L";
    public static final String GRIP_Z_SMALL = "GRIP_Z_S";
    public static final String GRIP_Z_LARGE = "GRIP_Z_L";
    public static final String CLAMP_NARROW = "CLAMP_NARROW";
    public static final String CLAMP_WIDE = "CLAMP_WIDE";

    private final String geometryProfileId;
    private final String fillerNozzleZ;
    private final String capperGripZ;
    private final String clampSetpoint;

    private M4GeometryProfileV1(
        String geometryProfileId,
        String fillerNozzleZ,
        String capperGripZ,
        String clampSetpoint
    ) {
        this.geometryProfileId = geometryProfileId;
        this.fillerNozzleZ = fillerNozzleZ;
        this.capperGripZ = capperGripZ;
        this.clampSetpoint = clampSetpoint;
    }

    public static M4GeometryProfileV1 forId(String geometryProfileId) {
        if (M4BottleContextV1.GEOMETRY_SMALL.equals(geometryProfileId)) {
            return new M4GeometryProfileV1(
                geometryProfileId,
                NOZZLE_Z_SMALL,
                GRIP_Z_SMALL,
                CLAMP_NARROW
            );
        }
        if (M4BottleContextV1.GEOMETRY_LARGE.equals(geometryProfileId)) {
            return new M4GeometryProfileV1(
                geometryProfileId,
                NOZZLE_Z_LARGE,
                GRIP_Z_LARGE,
                CLAMP_WIDE
            );
        }
        throw new IllegalArgumentException("unknown M4 geometry profile");
    }

    public String getGeometryProfileId() { return geometryProfileId; }
    public String getFillerNozzleZ() { return fillerNozzleZ; }
    public String getCapperGripZ() { return capperGripZ; }
    public String getClampSetpoint() { return clampSetpoint; }
}
