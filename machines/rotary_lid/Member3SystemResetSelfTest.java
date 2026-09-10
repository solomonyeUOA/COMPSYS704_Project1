/** Deterministic checks for the M3 side of M1's system-reset barrier. */
public final class Member3SystemResetSelfTest {
    private Member3SystemResetSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        testControllerResetFromOperatingStates();
        testPlantResetClearsWorkInFlight();
        testResetBarrierAndQuarantine();
        testConcurrentResetDoesNotDeadlock();
        System.out.println("MEMBER3_SYSTEM_RESET_SELF_TEST_PASSED");
    }

    private static void testControllerResetFromOperatingStates() {
        Member3MachineStateV1.reset();
        require(Member3MachineStateV1.requestRotation(true),
            "rotary busy reset precondition");
        Member3MachineStateV1.systemReset();
        require(Member3MachineStateV1.isResetSafe(),
            "reset clears rotary BUSY and motor output");

        require(Member3MachineStateV1.requestRotation(true),
            "rotary done reset precondition");
        Member3MachineStateV1.tickRotary(
            RotaryControllerModelV1.ROTATION_TIME_MS, false
        );
        Member3MachineStateV1.tickRotary(0, true);
        require(Member3MachineStateV1.getRotaryStatus() ==
            Member3MachineStateV1.DONE, "rotary reaches DONE");
        Member3MachineStateV1.systemReset();
        require(Member3MachineStateV1.isResetSafe(),
            "reset clears rotary DONE and completion latch");
        require(!Member3MachineStateV1.takeRotationDoneEvent(),
            "no stale rotary completion remains");

        require(Member3MachineStateV1.requestRotation(true),
            "rotary fault reset precondition");
        Member3MachineStateV1.tickRotary(
            RotaryControllerModelV1.ROTATION_TIME_MS, false
        );
        Member3MachineStateV1.tickRotary(
            RotaryControllerModelV1.ALIGNMENT_TIMEOUT_MS, false
        );
        require(Member3MachineStateV1.getRotaryStatus() ==
            Member3MachineStateV1.FAULT, "rotary reaches FAULT");
        Member3MachineStateV1.systemReset();
        require(Member3MachineStateV1.isResetSafe(),
            "reset clears rotary FAULT safely");

        require(Member3MachineStateV1.requestLidLoad("RST-LID-PICK", true),
            "lid PICKING reset precondition");
        Member3MachineStateV1.systemReset();
        require(Member3MachineStateV1.isResetSafe(),
            "reset clears lid PICKING and pick output");

        require(Member3MachineStateV1.requestLidLoad("RST-LID-PLACE", true),
            "lid PLACING reset precondition");
        Member3MachineStateV1.tickLidLoader(0, true, false);
        require(Member3MachineStateV1.getLidStatus() ==
            Member3MachineStateV1.BUSY, "lid reaches PLACING");
        Member3MachineStateV1.systemReset();
        require(Member3MachineStateV1.isResetSafe(),
            "reset clears lid PLACING and place output");

        require(Member3MachineStateV1.requestLidLoad("RST-LID-DONE", true),
            "lid DONE reset precondition");
        Member3MachineStateV1.tickLidLoader(0, true, false);
        Member3MachineStateV1.tickLidLoader(0, false, true);
        require(Member3MachineStateV1.getLidStatus() ==
            Member3MachineStateV1.DONE, "lid reaches DONE");
        Member3MachineStateV1.systemReset();
        require(Member3MachineStateV1.isResetSafe(),
            "reset clears lid DONE");
        require(Member3MachineStateV1.takeLidDoneBottleId() == null,
            "no stale lid completion remains");

        require(!Member3MachineStateV1.requestLidLoad(
            "RST-LID-FAULT", false
        ), "lid FAULT reset precondition");
        require(Member3MachineStateV1.getLidStatus() ==
            Member3MachineStateV1.FAULT, "lid reaches FAULT");
        Member3MachineStateV1.systemReset();
        require(Member3MachineStateV1.isResetSafe(),
            "reset clears lid FAULT safely");
    }

    private static void testPlantResetClearsWorkInFlight() {
        Member3PlantStateV1.reset();
        require(Member3PlantStateV1.registerBottleContext(
            "RST-PLANT|S|200|GEOM_S|PACK_S"
        ), "Plant reset context precondition");
        require(Member3PlantStateV1.loadBottle("RST-PLANT"),
            "Plant reset bottle precondition");
        Member3PlantStateV1.setRotaryMotor(true, 1L);
        Member3PlantStateV1.setPickCommand(true);
        int inventory = Member3PlantStateV1.getLidMagazineCount();
        Member3PlantStateV1.systemReset();
        require(Member3PlantStateV1.isResetSafe(),
            "Plant reset stops rotary and homes lid actuator");
        require(Member3PlantStateV1.getLidMagazineCount() == inventory,
            "Plant reset preserves physical inventory");
        require(Member3PlantStateV1.nextFillOfferWindow() == null &&
            Member3PlantStateV1.nextCapOfferWindow() == null &&
            Member3PlantStateV1.nextLabelOfferWindow() == null,
            "Plant reset clears pending hand-off offers");
        for (int position = 0; position < 6; position++) {
            require("empty".equals(
                Member3PlantStateV1.positionLabel(position)
            ), "Plant reset clears bottle slot " + (position + 1));
        }
        require(!Member3PlantStateV1.loadBottle("RST-PLANT"),
            "reset retires in-flight bottle identity");
    }

    private static void testResetBarrierAndQuarantine() throws Exception {
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
        require(!Member3MachineStateV1.requestRotation(true),
            "quarantine must block a stale rotation-ready signal");
        require(!Member3MachineStateV1.requestLidLoad("M3-RST-LATE", true),
            "quarantine must block a stale lid-position signal");
        require(!Member3PlantStateV1.loadBottle("M3-RST-LATE"),
            "quarantine must block stale Plant input");
        Member3PlantStateV1.setRotaryMotor(true, 999L);
        require(Member3PlantStateV1.isResetSafe(),
            "quarantine must not re-energise a Plant actuator");
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
    }

    private static void testConcurrentResetDoesNotDeadlock()
        throws Exception {
        Member3MachineStateV1.reset();
        Member3PlantStateV1.reset();
        final Throwable[] failure = new Throwable[1];

        Thread activity = new Thread(new Runnable() {
            public void run() {
                try {
                    for (int index = 0; index < 500; index++) {
                        Member3PlantStateV1.loadBottle(
                            "RST-CONCURRENT-" + index
                        );
                        Member3MachineStateV1.requestRotation(true);
                    }
                }
                catch (Throwable problem) {
                    failure[0] = problem;
                }
            }
        }, "m3-reset-activity");
        Thread reset = new Thread(new Runnable() {
            public void run() {
                try {
                    require(M3SystemResetStateV1.accept("RST8300"),
                        "concurrent reset accepted");
                }
                catch (Throwable problem) {
                    failure[0] = problem;
                }
            }
        }, "m3-reset-barrier");

        activity.start();
        reset.start();
        activity.join(2000L);
        reset.join(2000L);
        require(!activity.isAlive() && !reset.isAlive(),
            "concurrent reset and activity must not deadlock");
        require(failure[0] == null,
            "concurrent reset must not raise an exception");
        require("RST8300".equals(awaitAck()),
            "concurrent reset reaches its ACK barrier");
        require(Member3MachineStateV1.isResetSafe() &&
            Member3PlantStateV1.isResetSafe(),
            "concurrent reset ends in the M3 safe state");
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
