/** Repeated automatic recovery and fail-closed policy evaluation. */
public final class FaultRecoveryStressSelfTest {
    public static void main(String[] args) {
        int rounds = args.length == 0 ? 100 : Integer.parseInt(args[0]);
        for (int i = 0; i < rounds; i++) {
            FaultSupervisorSelfTest.main(new String[0]);
        }
        System.out.println("FaultRecoveryStressSelfTest PASS rounds=" + rounds);
    }
}
