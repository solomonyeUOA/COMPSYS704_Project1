import java.lang.reflect.Field;
import java.util.Properties;

/** Deterministic timing boundaries; run again with the startup slowdown property. */
public final class M4SlowDemoTimingSelfTest {
    private static int assertions;
    private static final String[] PROPERTIES = {
        "m4.plant.shortDelayMs", "m4.plant.doseDelayMs",
        "m4.plant.refillDelayMs", "m4.operationTimeoutMs"
    };

    public static void main(String[] args) throws Exception {
        String[] saved = new String[PROPERTIES.length];
        for (int i = 0; i < PROPERTIES.length; i++) {
            saved[i] = System.getProperty(PROPERTIES[i]);
            System.clearProperty(PROPERTIES[i]);
        }
        try {
            verifyRuntimeConfiguration(100L, 250L, 150L, 2500L);
            System.setProperty(PROPERTIES[0], "123");
            System.setProperty(PROPERTIES[1], "234");
            System.setProperty(PROPERTIES[2], "345");
            System.setProperty(PROPERTIES[3], "4567");
            verifyRuntimeConfiguration(123L, 234L, 345L, 4567L);
            verifyFillingAndImmediateStop();
            verifyCapperSafetyHome();
            verifySortAndTimeout();
            verifyUnchangedTransportAndRecognition();
        }
        finally {
            for (int i = 0; i < PROPERTIES.length; i++) {
                if (saved[i] == null) { System.clearProperty(PROPERTIES[i]); }
                else { System.setProperty(PROPERTIES[i], saved[i]); }
            }
        }
        System.out.println("M4SlowDemoTimingSelfTest PASSED " + assertions +
            " assertions; slowdown=" + SimulationTiming.slowdown());
    }

    private static void verifyRuntimeConfiguration(long shortDelay, long dose,
        long refill, long timeout) throws Exception {
        Member4PlantStateV1.reset();
        Member4MachineStateV1.reset();
        for (String name : new String[] {"fillerA", "fillerB"}) {
            Object plant = field(Member4PlantStateV1.class, name);
            require(number(plant, "geometryDelayMs") == scale(shortDelay),
                name + " geometry scales the configured value");
            require(number(plant, "doseDelayMs") == scale(dose),
                name + " dose scales the configured value");
            require(number(plant, "refillDelayMs") == scale(refill),
                name + " refill scales the configured value");
        }
        Object capper = field(Member4PlantStateV1.class, "capper");
        require(number(capper, "actionDelayMs") == scale(shortDelay),
            "capper production delay scales");
        require(number(capper, "resetActionDelayMs") == shortDelay,
            "capper reset keeps the unscaled physical delay");
        Object sort = field(Member4PlantStateV1.class, "sortPack");
        require(number(sort, "routeDelayMs") == scale(shortDelay) &&
            number(sort, "placeDelayMs") == scale(shortDelay),
            "both sort and pack physical operations scale");
        for (String name : new String[] {"fillerA", "fillerB", "capper", "sortPack"}) {
            require(number(field(Member4MachineStateV1.class, name), "timeoutMs") ==
                scale(timeout), name + " timeout scales with physical operations");
        }
    }

    private static void verifyFillingAndImmediateStop() throws Exception {
        FillerPlantModelV1 plant = (FillerPlantModelV1)
            field(Member4PlantStateV1.class, "fillerA");
        require(plant.acceptCommand("SLOW-FILL|SET_GEOMETRY|GEOM_S", 0L),
            "geometry accepted");
        long now = scale(123L);
        plant.tick(now - 1L);
        require(plant.takeFeedback() == null, "geometry cannot finish early");
        plant.tick(now);
        require("SLOW-FILL|PROFILE_CONFIRMED|GEOM_S".equals(plant.takeFeedback()),
            "geometry confirmation occurs at scaled boundary");
        require(plant.acceptCommand("SLOW-FILL|START_DOSE|120", now), "dose starts");
        plant.tick(now + scale(234L) - 1L);
        require(plant.takeFeedback() == null && plant.isInjectorOpen() &&
            plant.isDoseUnitMoving(), "dose visibly remains active until boundary");
        now += scale(234L);
        plant.tick(now);
        require("SLOW-FILL|DOSE_DONE|120".equals(plant.takeFeedback()),
            "dose completion keeps actual measured volume");
        require(plant.acceptCommand("SLOW-FILL|START_REFILL|-", now), "refill starts");
        plant.tick(now + scale(345L) - 1L);
        require(plant.takeFeedback() == null && plant.isInletOpen() &&
            !plant.isInjectorOpen(), "refill keeps the valve interlock");
        now += scale(345L);
        plant.tick(now);
        require("SLOW-FILL|REFILL_DONE|-".equals(plant.takeFeedback()),
            "refill ends at scaled boundary");
        plant.acceptCommand("SLOW-FILL|FINISH|-", now);
        require(!plant.isInletOpen() && !plant.isInjectorOpen(),
            "FINISH closes valves immediately");

        FillerPlantModelV1 stopped = new FillerPlantModelV1(0L, scale(250L), 0L);
        stopped.acceptCommand("STOP|SET_GEOMETRY|GEOM_S", 0L);
        stopped.tick(0L);
        stopped.acceptCommand("STOP|START_DOSE|120", 0L);
        require(stopped.isInjectorOpen(), "safe-stop fixture is actively dosing");
        stopped.acceptCommand("STOP|SAFE_STOP|-", 1L);
        require(!stopped.isInjectorOpen() && !stopped.isInletOpen() &&
            !stopped.isDoseUnitMoving(), "SAFE_STOP is never demonstration-delayed");
        stopped.resetForSystem();
        require(stopped.isSystemResetSafe(), "filler reset is immediately safe");
    }

    private static void verifyCapperSafetyHome() throws Exception {
        CapperPlantModelV1 capper = (CapperPlantModelV1)
            field(Member4PlantStateV1.class, "capper");
        long now = 0L;
        for (String action : new String[] {"SET_GEOMETRY|GEOM_L", "CLAMP|-",
            "LOWER|-", "GRIP|-", "TWIST|-"}) {
            require(capper.acceptCommand("SLOW-CAP|" + action, now), action);
            capper.tick(now + scale(123L) - 1L);
            require(capper.takeFeedback() == null, action + " does not finish early");
            now += scale(123L);
            capper.tick(now);
            require(capper.takeFeedback() != null, action + " finishes at boundary");
        }
        require(capper.isLowered() && capper.isClamped() && capper.isGripping(),
            "reset fixture is lowered, clamped and gripping");
        capper.beginSystemReset(now);
        require(!capper.isGripping() && capper.isLowered() && capper.isClamped(),
            "reset stops grip immediately, retaining the protective clamp");
        capper.tickSystemReset(now + 122L);
        require(!capper.isSystemResetSafe() && capper.isLowered(),
            "reset still requires real home evidence");
        capper.tickSystemReset(now + 123L);
        require(capper.isLowered() && capper.isClamped(), "home precedes raise");
        capper.tickSystemReset(now + 246L);
        require(!capper.isLowered() && capper.isClamped(), "raise precedes unclamp");
        capper.tickSystemReset(now + 369L);
        require(capper.isSystemResetSafe(), "all three reset steps remain unscaled");
        require("GRIP_TWIST_STOPPED,HOME_CONFIRMED,RAISED_CONFIRMED,UNCLAMPED_CONFIRMED"
            .equals(capper.systemResetEvidence()), "ordered reset evidence retained");
        CapperPlantModelV1 legacy = new CapperPlantModelV1(17L);
        require(number(legacy, "actionDelayMs") == 17L &&
            number(legacy, "resetActionDelayMs") == 17L,
            "one-argument constructor preserves previous timing contract");
    }

    private static void verifySortAndTimeout() throws Exception {
        SortPackPlantModelV1 sort = (SortPackPlantModelV1)
            field(Member4PlantStateV1.class, "sortPack");
        require(sort.acceptCommand("SLOW-SORT|SET_LANE|LANE_S", 0L), "sort starts");
        sort.tick(scale(123L) - 1L);
        require(sort.takeFeedback() == null, "routing cannot finish early");
        sort.tick(scale(123L));
        require(sort.takeFeedback() != null, "routing finishes at scaled boundary");
        sort.resetForSystem();
        require(sort.isSystemResetSafe(), "sort reset is immediately safe");

        FillerControllerModelV1 controller = (FillerControllerModelV1)
            field(Member4MachineStateV1.class, "fillerA");
        controller.setRatio(60);
        require(controller.acceptBottleAtFill("TIMEOUT|S|200|GEOM_S|PACK_S", 100L),
            "controller timeout fixture accepted");
        controller.tick(100L + scale(4567L));
        require(controller.getStatus() == M4StatusV1.BUSY,
            "scaled timeout does not fault the slower physical operation early");
        controller.tick(101L + scale(4567L));
        require(controller.getStatus() == M4StatusV1.FAULT &&
            "TIMEOUT".equals(controller.getFaultReason()),
            "bounded safety timeout still faults after its scaled deadline");
    }

    private static void verifyUnchangedTransportAndRecognition() throws Exception {
        Object event = field(Member4MachineStateV1.class, "capperTelemetryEvent");
        require(number(event, "copyGapMs") == 50L,
            "telemetry stays responsive and uses the original retry period");
        RecognitionSimulatorStateV1 recognition =
            RecognitionSimulatorStateV1.batchDrivenFromProperties(new Properties(), 0L);
        require(number(recognition, "intervalMillis") == 1000L &&
            number(recognition, "requestGapMillis") == 100L &&
            number(recognition, "timeoutMillis") == 10000L,
            "recognition and delivery transport are not artificially slowed");
    }

    private static long scale(long millis) { return SimulationTiming.scaleMillis(millis); }

    /** Introspection avoids production-only getters or sleeps in a boundary test. */
    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        Field member = type.getDeclaredField(name);
        member.setAccessible(true);
        return member.get(target instanceof Class<?> ? null : target);
    }

    private static long number(Object target, String name) throws Exception {
        return ((Number) field(target, name)).longValue();
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) { throw new AssertionError(message); }
    }
}
