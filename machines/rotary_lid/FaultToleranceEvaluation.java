/** Repeatable fault matrix used to collect IP evaluation evidence. */
public final class FaultToleranceEvaluation {
    private static int planned;
    private static int passed;
    private static int automaticEligible;
    private static int automaticVerified;
    private static int unsafeOutputs;

    private FaultToleranceEvaluation() {
    }

    public static void main(String[] args) {
        System.out.println(
            "scenario,subsystem,fault,expected,observed,result"
        );
        automatic("FT-01", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING",
            "motor_off+occupancy_consistent", "arrival_confirmed");
        manual("FT-02", "TRANSFER", "DEPARTURE_TIMEOUT", "CRITICAL");
        manual("FT-03", "TRANSFER", "PHOTO_EYE_FAILURE", "CRITICAL");
        manual("FT-04", "TRANSFER", "POSITION_CONFLICT", "CRITICAL");
        manual("FT-05", "ROTARY", "ALIGNMENT_TIMEOUT", "WARNING");
        manual("FT-06", "ROTARY", "MOTOR_STALL", "CRITICAL");
        manual("FT-07", "ROTARY", "POSITION_SENSOR_FAILURE", "CRITICAL");
        resource("FT-08", "LID", "MAGAZINE_EMPTY", "RESOURCE");
        automatic("FT-09", "LID", "PICK_TIMEOUT", "WARNING",
            "actuator_home+no_lid_held", "lid_picked");
        manual("FT-10", "LID", "PLACEMENT_TIMEOUT", "CRITICAL");
        manual("FT-11", "LID", "LID_SENSOR_FAULT", "CRITICAL");

        System.out.println("SUMMARY planned=" + planned +
            " passed=" + passed +
            " coverage=" + percent(passed, planned) +
            " automaticVerified=" + automaticVerified + "/" +
            automaticEligible +
            " unsafeOutputs=" + unsafeOutputs);
        if (passed != planned || unsafeOutputs != 0 ||
            automaticVerified != automaticEligible) {
            throw new AssertionError("fault-tolerance evaluation failed");
        }
        System.out.println("FaultToleranceEvaluation PASSED");
    }

    private static void automatic(
        String scenario,
        String subsystem,
        String fault,
        String severity,
        String safeEvidence,
        String serviceEvidence
    ) {
        planned++;
        automaticEligible++;
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        boolean accepted = model.onFaultEvent(
            event(scenario, subsystem, fault, severity)
        );
        int attempt = model.getActiveAttempt();
        boolean ack = model.onRecoveryAck(
            "V2|" + scenario + "|EVAL|1|ACCEPTED|interlocks_ok|1"
        );
        boolean result = model.onRecoveryResult(
            "V2|" + scenario + "|EVAL|1|SUCCESS|" + safeEvidence +
            "|" + serviceEvidence + "|2"
        );
        boolean correct = accepted && attempt == 1 && ack && result &&
            model.getState() ==
                FaultSupervisorModelV2_1.State.RECOVERY_READY;
        if (correct) {
            passed++;
            automaticVerified++;
        }
        unsafeOutputs += model.metricsSnapshot().unsafeActuatorOutputs;
        row(scenario, subsystem, fault, "VERIFIED_RETRY", model, correct);
    }

    private static void manual(
        String scenario,
        String subsystem,
        String fault,
        String severity
    ) {
        planned++;
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        boolean accepted = model.onFaultEvent(
            event(scenario, subsystem, fault, severity)
        );
        boolean noRequest = model.takeRecoveryRequest() == null;
        boolean safeStop = model.onSafeStopAck(
            "V2|" + scenario + "|EVAL|SAFE_STOPPED|1"
        );
        boolean correct = accepted && noRequest && safeStop &&
            model.getState() == FaultSupervisorModelV2_1.State.LOCKED_OUT;
        if (correct) {
            passed++;
        }
        unsafeOutputs += model.metricsSnapshot().unsafeActuatorOutputs;
        row(scenario, subsystem, fault, "LOCKED_OUT", model, correct);
    }

    private static void resource(
        String scenario,
        String subsystem,
        String fault,
        String severity
    ) {
        planned++;
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        boolean accepted = model.onFaultEvent(
            event(scenario, subsystem, fault, severity)
        );
        boolean noAttempt = model.getActiveAttempt() == 0;
        boolean restored = model.confirmResourceRestored(
            scenario, true, 2
        );
        boolean correct = accepted && noAttempt && restored &&
            model.getState() ==
                FaultSupervisorModelV2_1.State.RECOVERY_READY;
        if (correct) {
            passed++;
        }
        unsafeOutputs += model.metricsSnapshot().unsafeActuatorOutputs;
        row(scenario, subsystem, fault, "RESOURCE_RESTORED", model,
            correct);
    }

    private static String event(
        String scenario,
        String subsystem,
        String fault,
        String severity
    ) {
        return "V2|" + scenario + "|EVAL|" + subsystem + "|" + fault +
            "|" + severity + "|B-EVAL|1";
    }

    private static void row(
        String scenario,
        String subsystem,
        String fault,
        String expected,
        FaultSupervisorModelV2_1 model,
        boolean correct
    ) {
        System.out.println(scenario + "," + subsystem + "," + fault +
            "," + expected + "," + model.getState().name() + "," +
            (correct ? "PASS" : "FAIL"));
    }

    private static String percent(int numerator, int denominator) {
        return denominator == 0 ? "0.0%" :
            String.format("%.1f%%", 100.0 * numerator / denominator);
    }
}
