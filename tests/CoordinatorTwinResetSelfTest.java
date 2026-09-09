/** Extra M1 integration protections without replacing member reset tests. */
public final class CoordinatorTwinResetSelfTest {
    public static void main(String[] args) {
        CoordinatorStateV1.resetForTest();
        require(CoordinatorStateV1.accept("PO0001|1|P1,L,60,40,1"), "order accepted");
        require("V1|PO0001|P1|60|40|1|L".equals(CoordinatorStateV1.nextTwinBatchContext()), "live recipe context includes size");
        require(CoordinatorStateV1.publishStartOrder(), "initial quantity is held");
        require(CoordinatorStateV1.recordFtSafeStopRequest("V2|OLD|GUI-TEST|SAFE_STOP|1"), "initial FT accepted");
        require(CoordinatorStateV1.beginSystemReset("RST0000", 10L), "zero reset id valid");
        require(!CoordinatorStateV1.publishStartOrder() && CoordinatorStateV1.nextTwinBatchContext() == null, "reset cancels start and twin context");
        require(!CoordinatorStateV1.recordSystemResetAck(2, "RST0001", 11L), "wrong ACK rejected");
        for (int member = 2; member <= 4; member++)
            CoordinatorStateV1.recordSystemResetAck(member, "RST0000", 20L);
        require(!CoordinatorStateV1.recordFtSafeStopRequest("V2|OLD|GUI-TEST|SAFE_STOP|1"), "retired in-flight FT ignored");
        require(!CoordinatorStateV1.recordFtSafeStopRequest("V2|UNSEEN|E01|SAFE_STOP|1"), "unseen pre-reset M2 epoch ignored");
        require(!CoordinatorStateV1.ftCoordinationHold, "reset hold cannot be resurrected");
        require(!CoordinatorStateV1.accept("PO0001|1|P1,L,60,40,1"), "retired order cannot reappear");
        require(CoordinatorStateV1.accept("PO0002|1|P1,S,50,50,1"), "new id accepted");
        for (long now = 20L; now < 5000L; now += 50L)
            CoordinatorStateV1.nextSystemResetComplete(now);
        require(CoordinatorStateV1.beginSystemReset("RST0002", 5000L), "newer reset accepted");
        for (int member = 2; member <= 4; member++)
            CoordinatorStateV1.recordSystemResetAck(member, "RST0002", 5010L);
        for (long now = 5010L; now < 10000L; now += 50L)
            CoordinatorStateV1.nextSystemResetComplete(now);
        require(!CoordinatorStateV1.beginSystemReset("RST0001", 10000L), "older unseen reset rejected");
        require(!CoordinatorStateV1.beginSystemReset("RST00002", 10000L), "same numeric reset alias rejected");
        require(!CoordinatorStateV1.accept("PO0001|1|P1,L,60,40,1"), "older-than-last order tombstone retained");
        System.out.println("CoordinatorTwinResetSelfTest PASSED");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
