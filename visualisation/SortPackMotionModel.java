/**
 * Display-only easing for the Sort / Pack schematic route.
 *
 * The live twin publishes confirmed waypoints, never a measured position, so
 * the flow model deliberately holds progress at 0 for the whole BUSY window and
 * publishes 100 only once SORTED is confirmed. That is honest evidence, but it
 * leaves this schematic completely motionless for the entire physical
 * operation, at every demo speed.
 *
 * This class fills in the picture and nothing else. It eases a route fraction
 * along the schematic while the resource is busy, stops at the diverter until
 * the twin actually confirms the sort, then runs the branch into the package.
 * It never writes back to the flow model, the twin, or any counter, and no
 * decision is taken from it. SimulationTiming paces it, so demo slowdown
 * lengthens the drawn motion exactly as it lengthens the simulated route and
 * place actions.
 */
final class SortPackMotionModel {
    /** Waypoint at the diverter; busy motion stops here awaiting confirmation. */
    static final double DIVERTER_FRACTION = 0.42;

    /** Matches the m4.plant.shortDelayMs default used for route and place. */
    private static final long ROUTE_BASE_MILLIS = 100L;
    private static final long PLACE_BASE_MILLIS = 100L;
    /** A stalled or coalesced EDT must never teleport the schematic. */
    private static final long MAX_FRAME_MILLIS = 120L;

    private final long routeMillis;
    private final long placeMillis;
    private String bottleKey = "";
    private double fraction;
    private long lastMillis;
    private boolean hasLast;

    SortPackMotionModel() {
        this(
            SimulationTiming.scaleMillis(ROUTE_BASE_MILLIS),
            SimulationTiming.scaleMillis(PLACE_BASE_MILLIS)
        );
    }

    SortPackMotionModel(long routeTravelMillis, long placeTravelMillis) {
        if (routeTravelMillis <= 0L || placeTravelMillis <= 0L) {
            throw new IllegalArgumentException("travel duration must be positive");
        }
        routeMillis = routeTravelMillis;
        placeMillis = placeTravelMillis;
    }

    /**
     * @param key       twin identity shown at Sort / Pack, empty when none is
     * @param running   the twin reports the Sort / Pack resource BUSY
     * @param confirmed the twin has confirmed this bottle's sort waypoint
     * @return schematic route fraction: 0 at the infeed, 1 inside the package
     */
    synchronized double observe(
        String key,
        boolean running,
        boolean confirmed,
        long nowMillis
    ) {
        String safeKey = key == null ? "" : key;
        if (!safeKey.equals(bottleKey)) {
            // A different identity is a different journey, never a continuation.
            bottleKey = safeKey;
            fraction = 0.0;
            hasLast = false;
        }
        if (safeKey.isEmpty()) {
            hasLast = false;
            return 0.0;
        }
        long elapsed = 0L;
        if (hasLast && nowMillis > lastMillis) {
            elapsed = Math.min(MAX_FRAME_MILLIS, nowMillis - lastMillis);
        }
        lastMillis = nowMillis;
        hasLast = true;
        if (confirmed && fraction >= DIVERTER_FRACTION) {
            fraction = advance(fraction, 1.0,
                (1.0 - DIVERTER_FRACTION) * elapsed / placeMillis);
        }
        else if (confirmed || running) {
            // A missed BUSY poll still walks the infeed rather than jumping it.
            fraction = advance(fraction, DIVERTER_FRACTION,
                DIVERTER_FRACTION * elapsed / routeMillis);
        }
        return fraction;
    }

    synchronized void reset() {
        bottleKey = "";
        fraction = 0.0;
        hasLast = false;
    }

    synchronized double getFraction() {
        return fraction;
    }

    private static double advance(double current, double target, double step) {
        if (current >= target) {
            return current;
        }
        double next = current + Math.max(0.0, step);
        return next >= target ? target : next;
    }
}
