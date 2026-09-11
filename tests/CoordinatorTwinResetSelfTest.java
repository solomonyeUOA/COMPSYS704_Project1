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
        require("RST0002".equals(CoordinatorStateV1.nextM2SystemReset(5000L)) &&
            "RST0002".equals(CoordinatorStateV1.nextM3SystemReset(5000L)) &&
            "RST0002".equals(CoordinatorStateV1.nextM4SystemReset(5000L)),
            "second reset replaces every completed fan-out payload");
        for (int member = 2; member <= 4; member++)
            CoordinatorStateV1.recordSystemResetAck(member, "RST0002", 5010L);
        for (long now = 5010L; now < 10000L; now += 50L)
            CoordinatorStateV1.nextSystemResetComplete(now);
        require(CoordinatorStateV1.beginSystemReset("RST1789167927465", 10000L), "external timestamp reset accepted");
        require("RST1789167927465".equals(
            CoordinatorStateV1.nextM2SystemReset(10000L)) &&
            "RST1789167927465".equals(
                CoordinatorStateV1.nextM3SystemReset(10000L)) &&
            "RST1789167927465".equals(
                CoordinatorStateV1.nextM4SystemReset(10000L)),
            "external reset replaces all prior fan-out payloads");
        for (int member = 2; member <= 4; member++)
            CoordinatorStateV1.recordSystemResetAck(member, "RST1789167927465", 10010L);
        for (long now = 10010L; now < 15000L; now += 50L)
            CoordinatorStateV1.nextSystemResetComplete(now);
        require(CoordinatorStateV1.beginSystemReset("RST0001", 15000L), "unseen POS reset accepted after timestamp reset");
        require("RST0001".equals(CoordinatorStateV1.nextM2SystemReset(15000L)) &&
            "RST0001".equals(CoordinatorStateV1.nextM3SystemReset(15000L)) &&
            "RST0001".equals(CoordinatorStateV1.nextM4SystemReset(15000L)),
            "third reset fans out the current POS identity");
        require(!CoordinatorStateV1.beginSystemReset("RST0002", 15010L), "completed reset identity remains rejected");
        require(!CoordinatorStateV1.accept("PO0001|1|P1,L,60,40,1"), "older-than-last order tombstone retained");
        System.out.println("CoordinatorTwinResetSelfTest PASSED");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
