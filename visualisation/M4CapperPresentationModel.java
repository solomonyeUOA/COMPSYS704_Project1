/**
 * Consumer-only projection of origin/main 4f5fefc M4CapperTelemetryV1.
 * Wire validation follows that official contract exactly; no controller state
 * or shared runtime helper is introduced into this older IP branch.
 */
final class M4CapperPresentationModel {
    private static final String[] STAGES = {
        "WAITING", "POSITIONING", "CLAMPING", "LOWERING", "GRIPPING",
        "TWISTING", "RELEASING", "RETURNING", "RAISING", "UNCLAMPING",
        "DONE", "FAULT"
    };
    private static final double[] POSITIONS = {
        0, 8, 18, 28, 38, 52, 62, 70, 82, 94, 100
    };

    static final class Snapshot {
        final String bottleId, sizeCode, geometryProfile, stage, payload;
        final int status;
        final double displayPosition;

        Snapshot(String[] fields, int value, double position) {
            bottleId = fields[1];
            sizeCode = fields[2];
            geometryProfile = fields[3];
            stage = fields[4];
            status = value;
            displayPosition = position;
            payload = "V1|" + bottleId + "|" + sizeCode + "|" +
                geometryProfile + "|" + stage + "|" + status;
        }

        boolean hasBottle() { return !"-".equals(bottleId); }
    }

    private Snapshot current;

    synchronized boolean accept(String payload) {
        if (payload == null) return false;
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 6 || !"V1".equals(fields[0])) return false;
        String bottle = fields[1];
        if (bottle.length() == 0 || !bottle.equals(bottle.trim()) ||
            bottle.indexOf('\r') >= 0 || bottle.indexOf('\n') >= 0) return false;
        boolean idle = "-".equals(fields[2]) && "-".equals(fields[3]);
        boolean small = "S".equals(fields[2]) && "GEOM_S".equals(fields[3]);
        boolean large = "L".equals(fields[2]) && "GEOM_L".equals(fields[3]);
        if ((!idle && !small && !large) || "-".equals(bottle) != idle) return false;
        int status;
        try { status = Integer.parseInt(fields[5]); }
        catch (NumberFormatException invalid) { return false; }
        if (status < 0 || status > 4) return false;
        int stage = -1;
        for (int index = 0; index < STAGES.length; index++) {
            if (STAGES[index].equals(fields[4])) stage = index;
        }
        if (stage < 0) return false;
        // FAULT has no position in the wire protocol. Freeze the last display
        // position for this bottle; never fall back to the advancing animation.
        double position = stage < POSITIONS.length ? POSITIONS[stage] :
            (current != null && current.bottleId.equals(bottle) ?
                current.displayPosition : 0.0);
        Snapshot next = new Snapshot(fields, status, position);
        if (current == null || !current.payload.equals(next.payload)) current = next;
        return true;
    }

    synchronized Snapshot snapshot() { return current; }
    synchronized void reset() { current = null; }
}
