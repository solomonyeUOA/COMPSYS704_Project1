/** Repeats existing physical-controller and M1 integration fixtures. */
public final class FaultControllerStressSelfTest {
    public static void main(String[] args) throws Exception {
        int rounds = args.length == 0 ? 20 : Integer.parseInt(args[0]);
        if (rounds < 1) throw new IllegalArgumentException("positive rounds required");
        String[] empty = new String[0];
        for (int i = 0; i < rounds; i++) {
            FaultInjectionIntegrationSelfTest.main(empty);
            Member2FaultInjectionIntegrationSelfTest.main(empty);
            Member2FaultAdapterSelfTest.main(empty);
            Member3ControllerSelfTest.main(empty);
            FaultSupervisorSelfTest.main(empty);
            Member2RepeatedOrdersSelfTest.main(empty);
            SystemWatchdogSelfTest.main(empty);
            System.out.println("STRESS_FIXTURE_ROUND " + (i + 1) + "/" + rounds + " PASS");
        }
        System.out.println("PASS fixtureRounds=" + rounds + " suites=" + (rounds * 7) +
            " fixturesResetBetweenCases=true");
    }
}
