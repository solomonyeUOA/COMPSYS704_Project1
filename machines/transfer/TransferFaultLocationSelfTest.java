/** Cause/location and deadline checks with deterministic controller time. */
public final class TransferFaultLocationSelfTest {
    public static void main(String[] args) {
        for (int i = 0; i < 1000; i++) {
            String id = "LOCATION-B" + i;
            for (boolean healthy : new boolean[] {false, true}) {
                ConveyorControllerModelV1 c = new ConveyorControllerModelV1();
                check(c.offerBottle(id + "|S|200|GEOM_S|PACK_S"), "profile");
                check(c.startTransfer(0), "start");
                check(!c.observeEntrySensors(true, false, false, "LOC"), "normal in transit");
                check(c.observeEntrySensors(healthy, true, false, "LOC"), "sensor failure detected");
                String code = healthy ? "POSITION_CONFLICT" : "PHOTO_EYE_FAILURE";
                check(code.equals(c.getFaultCode()), "distinct cause");
                check(!c.isMotorEnabled() && c.takeLoadBottle() == null, "no unsafe handoff");
                check(c.recoverInjectedFault(code, c.getStateVersion() - 1) < 0, "stale evidence rejected");
                check(c.recoverInjectedFault(code, c.getStateVersion()) >= 0, "manual evidence");
                check(c.startTransfer(10), "restart same bottle");
                check(c.acceptP1Feedback(id + "|true|true|true|true|true"), "fresh feedback");
                check(id.equals(c.takeLoadBottle()) && c.takeLoadBottle() == null, "exactly one handoff");
            }
            BottleUnloaderControllerModelV1 u = new BottleUnloaderControllerModelV1();
            check(u.acceptProfile(id + "|S|200|GEOM_S|PACK_S"), "unload profile");
            check(u.acceptUnloadReady(id) && id.equals(u.takeUnloadCommand()), "unload starts");
            check(u.armDepartureTimeout(100L, "LOC"), "missing departure armed");
            long deadline = 100L + SimulationTiming.scaleMillis(2000L);
            u.tickDeparture(deadline - 1);
            check(u.getStatus() == M2StatusV1.BUSY && u.takeFaultPayload() == null, "no early timeout");
            check(!u.acceptRemovalConfirmed(id + "|true", deadline - 1), "injected missing sensor evidence");
            check(u.takeP6Clear() == null && u.takeSortContext() == null && !u.isBottleDonePresent(deadline), "no false completion");
            u.tickDeparture(deadline);
            check(u.getStatus() == M2StatusV1.FAULT, "deadline faults");
            String[] f = u.takeFaultPayload().split("\\|", -1);
            check("DEPARTURE_TIMEOUT".equals(f[4]), "departure cause");
            check(u.recoverInjectedFault(f[4], Long.parseLong(f[7])) >= 0, "recover");
            check(id.equals(u.takeUnloadCommand()), "retry same bottle");
            check(u.acceptRemovalConfirmed(id + "|true", deadline + 1), "fresh departure evidence");
            check(id.equals(u.takeP6Clear()) && u.takeP6Clear() == null, "one P6 clear");
        }
        System.out.println("TransferFaultLocationSelfTest PASS: 3000 injection/recovery cycles");
    }
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }
}
