/** Verifies GUI requests reach real M3 controllers before the supervisor. */
public final class FaultInjectionIntegrationSelfTest {
    private static final String[] ROTARY_FAULTS = {
        "ALIGNMENT_TIMEOUT", "MOTOR_STALL", "POSITION_SENSOR_FAILURE"
    };
    private static final String[] LID_FAULTS = {
        "MAGAZINE_EMPTY", "PICK_TIMEOUT", "PLACEMENT_TIMEOUT",
        "LID_SENSOR_FAULT"
    };

    private FaultInjectionIntegrationSelfTest() {
    }

    public static void main(String[] args) {
        for (int index = 0; index < ROTARY_FAULTS.length; index++) {
            verifyRotary(ROTARY_FAULTS[index]);
        }
        for (int index = 0; index < LID_FAULTS.length; index++) {
            verifyLid(LID_FAULTS[index], index);
        }
        verifyTransferTransport();
        System.out.println("FaultInjectionIntegrationSelfTest PASSED (11 faults)");
    }

    private static void verifyRotary(String faultCode) {
        Member3MachineStateV1.reset();
        require(FaultInjectionStateV2_1.arm(faultCode), "arm " + faultCode);
        require(Member3MachineStateV1.requestRotation(true),
            "rotation operation accepts " + faultCode);
        require(Member3MachineStateV1.getRotaryStatus() ==
            Member3MachineStateV1.FAULT, faultCode + " reaches controller");
        require(!Member3MachineStateV1.isRotaryMotorEnabled(),
            faultCode + " de-energises rotary motor");
        require(faultCode.equals(FaultSupervisorStateV2_1.activeFaultCode()),
            faultCode + " reaches supervisor");
        require("-".equals(FaultInjectionStateV2_1.armedFault()),
            faultCode + " is consumed once");
    }

    private static void verifyLid(String faultCode, int index) {
        Member3MachineStateV1.reset();
        require(FaultInjectionStateV2_1.arm(faultCode), "arm " + faultCode);
        require(Member3MachineStateV1.requestLidLoad(
            "TEST-LID-B00" + (index + 1), true
        ), "lid operation accepts " + faultCode);
        require(Member3MachineStateV1.getLidStatus() ==
            Member3MachineStateV1.FAULT, faultCode + " reaches controller");
        require(!Member3MachineStateV1.isLidPickEnabled() &&
            !Member3MachineStateV1.isLidPlaceEnabled(),
            faultCode + " de-energises lid actuators");
        require(faultCode.equals(FaultSupervisorStateV2_1.activeFaultCode()),
            faultCode + " reaches supervisor");
        require("-".equals(FaultInjectionStateV2_1.armedFault()),
            faultCode + " is consumed once");
    }

    private static void verifyTransferTransport() {
        FaultInjectionStateV2_1.reset();
        require(FaultInjectionStateV2_1.arm("ARRIVAL_TIMEOUT"),
            "arm transfer fault");
        String request = FaultInjectionStateV2_1.nextTransferRequest();
        require(request != null && request.endsWith("|ARRIVAL_TIMEOUT"),
            "transfer fault crosses the M3 SystemJ boundary");
        FaultInjectionStateV2_1.consumed("ARRIVAL_TIMEOUT");
        require(FaultInjectionStateV2_1.arm("ARRIVAL_TIMEOUT"),
            "same transfer fault can be armed again immediately");
        String repeated = FaultInjectionStateV2_1.nextTransferRequest();
        require(repeated != null && repeated.endsWith("|ARRIVAL_TIMEOUT") &&
            !repeated.equals(request),
            "repeated injection uses a new request identity");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
