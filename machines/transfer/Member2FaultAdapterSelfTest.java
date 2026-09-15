/** Frozen V2.1 transfer-fault protocol and idempotency checks. */
public final class Member2FaultAdapterSelfTest {
    public static void main(String[] args) {
        M2TransferFaultAdapterModelV2_1 adapter =
            new M2TransferFaultAdapterModelV2_1();
        String event =
            "V2|F021|E07|TRANSFER|ARRIVAL_TIMEOUT|WARNING|B104|18";
        check(adapter.onLocalFault(event), "accept local fault");
        check(event.equals(adapter.takeFaultEvent()),
            "forward exact fault payload");

        String request = "V2|F021|E07|RETRY_TRANSFER|1|18";
        check(adapter.onRecoveryRequest(request), "accept one retry");
        String ack = adapter.takeAck();
        check("V2|F021|E07|1|ACCEPTED|route_clear|18".equals(ack),
            "exact ACK schema");
        check(request.equals(adapter.takeIntent()),
            "Controller receives abstract intent only");

        check(adapter.onRecoveryRequest(request),
            "duplicate request is idempotent");
        check(ack.equals(adapter.takeAck()), "duplicate returns same ACK");
        check(adapter.takeIntent() == null,
            "duplicate does not repeat physical intent");

        String result = "V2|F021|E07|1|SUCCESS|" +
            "motor_off+occupancy_consistent|arrival_confirmed|19";
        check(adapter.onLocalRecoveryEvidence(result),
            "accept matching independent evidence");
        check(result.equals(adapter.takeResult()),
            "forward exact result payload");

        M2TransferFaultAdapterModelV2_1 stale =
            new M2TransferFaultAdapterModelV2_1();
        check(stale.onLocalFault(event), "second adapter fault");
        check(!stale.onRecoveryRequest(
            "V2|F021|E07|RETRY_TRANSFER|1|17"
        ), "reject stale state version");
        check(stale.takeAck().contains("|REJECTED|"),
            "rejected request is acknowledged safely");

        M2TransferFaultAdapterModelV2_1 reliable =
            new M2TransferFaultAdapterModelV2_1();
        check(reliable.onLocalFault(event), "reliable adapter accepts fault");
        check(event.equals(reliable.takeFaultEvent(1000L)),
            "first fault event is emitted");
        check(reliable.takeFaultEvent(1499L) == null,
            "fault retry is rate limited");
        check(event.equals(reliable.takeFaultEvent(1500L)),
            "unacknowledged fault event is retransmitted");
        check(reliable.onLocalRecoveryEvidence(result),
            "reliable adapter accepts recovery completion");
        check(reliable.takeFaultEvent(2000L) == null,
            "retransmission stops after recovery completion");

        System.out.println("Member2FaultAdapterSelfTest PASSED");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
