/** Verifies M3 test requests fault the real M2 transfer controllers. */
public final class Member2FaultInjectionIntegrationSelfTest {
    private static final String PROFILE = "TEST-M2-B001|S|200|GEOM_S|PACK_S";

    private Member2FaultInjectionIntegrationSelfTest() {
    }

    public static void main(String[] args) {
        verifyArrivalTimeout();
        verifyUnloaderFault("DEPARTURE_TIMEOUT", 2);
        verifyUnloaderFault("PHOTO_EYE_FAILURE", 3);
        verifyUnloaderFault("POSITION_CONFLICT", 4);
        System.out.println(
            "Member2FaultInjectionIntegrationSelfTest PASSED (4 faults)"
        );
    }

    private static void verifyArrivalTimeout() {
        M2MachineStateV1.reset();
        M2TransferFaultAdapterStateV2_1.reset();
        require(M2TransferFaultAdapterStateV2_1.armTestFault(
            "GUI-1|ARRIVAL_TIMEOUT"
        ), "adapter accepts arrival injection");
        require(M2MachineStateV1.offerConveyorBottle(PROFILE),
            "offer conveyor bottle");
        require(M2MachineStateV1.nextConveyorTransferOffer() != null,
            "start real conveyor operation");
        require(M2MachineStateV1.getConveyorStatus() == M2StatusV1.FAULT,
            "real conveyor enters fault");
        require(!M2MachineStateV1.isConveyorMotorEnabled(),
            "arrival timeout stops conveyor motor");
        verifyEvent(M2MachineStateV1.takeConveyorFault(), "ARRIVAL_TIMEOUT");
    }

    private static void verifyUnloaderFault(String faultCode, int sequence) {
        M2MachineStateV1.reset();
        M2TransferFaultAdapterStateV2_1.reset();
        require(M2TransferFaultAdapterStateV2_1.armTestFault(
            "GUI-" + sequence + "|" + faultCode
        ), "adapter accepts " + faultCode);
        require(M2MachineStateV1.acceptUnloadProfile(PROFILE),
            "accept unload profile");
        require(M2MachineStateV1.acceptUnloadReady("TEST-M2-B001"),
            "accept unload-ready evidence");
        require(M2MachineStateV1.takeUnloadCommand() == null,
            faultCode + " suppresses physical unload command");
        require(M2MachineStateV1.getUnloaderStatus() == M2StatusV1.FAULT,
            faultCode + " faults real unloader");
        String event = M2MachineStateV1.takeUnloaderFault();
        verifyEvent(event, faultCode);
        String[] fields = event.split("\\|", -1);
        require(M2TransferFaultAdapterStateV2_1.recoverTestFault(
            "V2|" + fields[1] + "|" + fields[2] + "|" + fields[4] +
            "|MANUAL_RECOVER|" + fields[7]
        ), faultCode + " accepts evidence-gated recovery");
        require(M2MachineStateV1.getUnloaderStatus() == M2StatusV1.READY,
            faultCode + " exits physical isolation");
        String result = M2TransferFaultAdapterStateV2_1.takeResult();
        require(result != null && result.contains(
            "|SUCCESS|motor_off+occupancy_consistent|location_confirmed|"
        ), faultCode + " returns real controller evidence");
        require(M2MachineStateV1.nextUnloadCommandOffer() != null,
            faultCode + " retries the interrupted unload command");
    }

    private static void verifyEvent(String event, String faultCode) {
        require(event != null && event.contains("|TRANSFER|" + faultCode + "|"),
            faultCode + " produces V2.1 controller event");
        require(M2TransferFaultAdapterStateV2_1.onLocalFault(event),
            faultCode + " enters transfer adapter");
        require(event.equals(M2TransferFaultAdapterStateV2_1.takeFaultEvent()),
            faultCode + " is forwarded unchanged to M3");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
