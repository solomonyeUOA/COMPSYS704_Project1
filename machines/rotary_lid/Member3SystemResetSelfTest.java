/** Deterministic checks for the M3 side of M1's system-reset barrier. */
public final class Member3SystemResetSelfTest {
    private Member3SystemResetSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Member3MachineStateV1.reset();
        Member3PlantStateV1.reset();

        int initialLids = Member3PlantStateV1.getLidMagazineCount();
        Member3PlantStateV1.setPickCommand(true);
        Thread.sleep(LidLoaderPlantModelV1.PICK_TIME_MS + 25L);
        Member3PlantStateV1.updateLidLoader();
        Member3PlantStateV1.setPickCommand(false);
        Member3PlantStateV1.setPlaceCommand(true);
        Thread.sleep(LidLoaderPlantModelV1.PLACE_TIME_MS + 25L);
        Member3PlantStateV1.updateLidLoader();
        require(Member3PlantStateV1.getLidMagazineCount() == initialLids - 1,
            "precondition must consume one lid");

        require(Member3PlantStateV1.loadBottle("M3-RST-B001"),
            "Plant must contain a bottle before reset");
        require(Member3MachineStateV1.requestRotation(true),
            "rotary Controller must be busy before reset");
        require(M3SystemResetStateV1.accept("RST8201"),
            "valid reset must be accepted");
        require(M3SystemResetStateV1.isQuarantined(),
            "M3 must quarantine old one-shot messages");
        require(Member3MachineStateV1.isResetSafe(),
            "Controllers must stop immediately");
        require(Member3PlantStateV1.isResetSafe(),
            "Plants must be home and empty-handed");
        require(Member3PlantStateV1.getLidMagazineCount() == initialLids - 1,
            "reset must preserve current lid inventory");
        require(!Member3PlantStateV1.loadBottle("M3-RST-B001"),
            "retired bottle identity must be rejected");

        String ack = awaitAck();
        require("RST8201".equals(ack),
            "ACK must contain the unchanged resetId");
        require(M3SystemResetStateV1.accept("RST8201"),
            "duplicate reset must be idempotent");
        require(!M3SystemResetStateV1.accept("RST8200"),
            "older resetId must be rejected");
        require(Member3PlantStateV1.loadBottle("M3-RST-B002"),
            "new production must work after reset");

        System.out.println("MEMBER3_SYSTEM_RESET_SELF_TEST_PASSED");
    }

    private static String awaitAck() throws Exception {
        long deadline = System.currentTimeMillis() + 2500L;
        while (System.currentTimeMillis() < deadline) {
            String ack = M3SystemResetStateV1.takeAck();
            if (ack != null) {
                return ack;
            }
            Thread.sleep(20L);
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
