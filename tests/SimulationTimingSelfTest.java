public final class SimulationTimingSelfTest {
    public static void main(String[] args) {
        check(SimulationTiming.parseSlowdown("1") == 1.0, "normal default");
        check(SimulationTiming.parseSlowdown(" 5 ") == 5.0, "demo factor");
        check(SimulationTiming.parseSlowdown("1.5") == 1.5, "fractional factor");
        for (String invalid : new String[] {"", "abc", "0", "-5", "0.5", "10.01", "NaN", "Infinity", "-Infinity"}) {
            try { SimulationTiming.parseSlowdown(invalid); throw new AssertionError("accepted " + invalid); }
            catch (IllegalArgumentException expected) { }
        }
        check(SimulationTiming.scaleMillis(100L, 1.0) == 100L, "normal timing unchanged");
        check(SimulationTiming.scaleMillis(100L, 5.0) == 500L, "physical action lengthened");
        check(SimulationTiming.scaleMillis(2500L, 5.0) == 12500L, "matching timeout lengthened");
        check(SimulationTiming.scaleMillis(1L, 1.5) == 2L, "round up, never shorten");
        check(SimulationTiming.scaleMillis(0L, 10.0) == 0L, "zero-delay model remains possible");
        check(SimulationTiming.scaleMillis(Long.MAX_VALUE, 1.0) == Long.MAX_VALUE, "no double overflow rounding");
        try { SimulationTiming.scaleMillis(Long.MAX_VALUE, 2.0); throw new AssertionError("overflow accepted"); }
        catch (IllegalArgumentException expected) { }
        try { SimulationTiming.scaleMillis(-1L, 1.0); throw new AssertionError("negative duration accepted"); }
        catch (IllegalArgumentException expected) { }
        check(SimulationTiming.scaleMillis(100L) == (long)Math.ceil(100.0 * SimulationTiming.slowdown()),
            "runtime property consistently applied");
        System.out.println("SimulationTimingSelfTest PASS slowdown=" + SimulationTiming.slowdown());
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
