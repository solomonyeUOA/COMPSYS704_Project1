import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Opt-in pacing for the software simulation, fixed for the lifetime of a JVM.
 * Scale physical action durations and matching operation timeouts ONLY.
 * Never scale clocks, timestamps, identities, telemetry, transport, or heartbeat
 * deadlines. This is not a hardware speed control.
 */
public final class SimulationTiming {
    public static final String PROPERTY = "abs.simulation.slowdown";
    private static final double SLOWDOWN = parseSlowdown(System.getProperty(PROPERTY, "1"));

    private SimulationTiming() { }

    public static double slowdown() { return SLOWDOWN; }

    public static long scaleMillis(long baseMillis) {
        return scaleMillis(baseMillis, SLOWDOWN);
    }

    static double parseSlowdown(String value) {
        final double factor;
        try { factor = Double.parseDouble(value.trim()); }
        catch (RuntimeException invalid) {
            throw new IllegalArgumentException(PROPERTY + " must be a finite number from 1 to 10", invalid);
        }
        if (Double.isNaN(factor) || Double.isInfinite(factor) || factor < 1.0 || factor > 10.0)
            throw new IllegalArgumentException(PROPERTY + " must be a finite number from 1 to 10");
        return factor;
    }

    static long scaleMillis(long baseMillis, double factor) {
        if (baseMillis < 0L) throw new IllegalArgumentException("duration must not be negative");
        parseSlowdown(Double.toString(factor));
        try {
            return BigDecimal.valueOf(baseMillis).multiply(BigDecimal.valueOf(factor))
                .setScale(0, RoundingMode.CEILING).longValueExact();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("scaled simulation duration is too large", overflow);
        }
    }

    public static String demoSuffix() {
        return SLOWDOWN == 1.0 ? "" : " - DEMO " + SLOWDOWN + "x slower actions";
    }
}
