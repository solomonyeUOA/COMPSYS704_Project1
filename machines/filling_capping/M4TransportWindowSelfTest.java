/** Regression for the q100 B059 controller/Plant transport-window stall. */
public final class M4TransportWindowSelfTest {
    private M4TransportWindowSelfTest() { }

    public static void main(String[] args) {
        ackStopsObsoleteCopies();
        lateCapperCopiesAreIdempotent();
        int completed = 0;
        for (int bottle = 1; bottle <= 100; bottle++) {
            if (runBottle(bottle, bottle == 59)) { completed++; }
        }
        require(completed == 100,
            "all q100 filler cycles survive the bounded scheduling pause");
        System.out.println("M4TransportWindowSelfTest PASSED");
    }

    private static void ackStopsObsoleteCopies() {
        M4BoundedEventV1 event = M4BoundedEventV1.newLocalControlEvent();
        event.publish("Q100-B001|FINISH|-", 0L);
        require(event.take(0L) != null, "first control copy is available");
        event.cancel();
        require(event.take(100L) == null && !event.isPending(),
            "semantic feedback acknowledgement cancels obsolete copies");
    }

    private static void lateCapperCopiesAreIdempotent() {
        CapperPlantModelV1 plant = new CapperPlantModelV1(0L);
        String first = "Q100-B032";
        String[] actions = {
            "SET_GEOMETRY|GEOM_L", "CLAMP|-", "LOWER|-", "GRIP|-",
            "TWIST|-", "RELEASE|-", "RETURN_HOME|-", "RAISE|-",
            "UNCLAMP|-"
        };
        for (int index = 0; index < actions.length; index++) {
            require(plant.acceptCommand(first + "|" + actions[index], index),
                "Capper Plant accepts ordered action " + actions[index]);
            plant.tick(index);
            plant.takeFeedback();
        }
        require("COMPLETE".equals(plant.getStageName()),
            "first Capper bottle completes");
        require(plant.acceptCommand(
            "Q100-B033|SET_GEOMETRY|GEOM_L", 20L
        ), "next Capper bottle starts");
        require(plant.acceptCommand(first + "|UNCLAMP|-", 21L) &&
            "POSITIONING".equals(plant.getStageName()),
            "late command from retired bottle is an idempotent no-op");

        CapperControllerModelV1 controller =
            new CapperControllerModelV1(2500L);
        require(controller.acceptBottleAtCap(
            first + "|L|500|GEOM_L|PACK_L", 0L
        ), "first Capper controller cycle starts");
        String[] feedback = {
            "PROFILE_CONFIRMED|GEOM_L", "CLAMPED|-", "LOWERED|-",
            "GRIPPED|-", "TWISTED|-", "RELEASED|-", "HOME|-",
            "RAISED|-", "UNCLAMPED|-"
        };
        for (int index = 0; index < feedback.length; index++) {
            controller.acceptPlantFeedback(
                first + "|" + feedback[index], index
            );
        }
        require(controller.acceptBottleAtCap(
            "Q100-B033|L|500|GEOM_L|PACK_L", 20L
        ), "next Capper controller cycle starts");
        controller.acceptPlantFeedback(first + "|UNCLAMPED|-", 21L);
        require("POSITIONING".equals(controller.getStageName()),
            "late feedback from completed bottle is an idempotent no-op");
        controller.acceptPlantFeedback(
            "Q100-B033|PROFILE_CONFIRMED|GEOM_L", 22L
        );
        controller.acceptPlantFeedback(
            "Q100-B033|PROFILE_CONFIRMED|GEOM_L", 23L
        );
        require("CLAMPING".equals(controller.getStageName()),
            "same-bottle feedback duplicate cannot rewind the stage");
    }

    private static boolean runBottle(int number, boolean pauseAtSafeWait) {
        FillerControllerModelV1 controller = new FillerControllerModelV1(
            FillerControllerModelV1.LIQUID_B, 0, 2500L
        );
        FillerPlantModelV1 plant = new FillerPlantModelV1(
            100L, 250L, 150L
        );
        M4BoundedEventV1 commands =
            M4BoundedEventV1.newLocalControlEvent();
        M4BoundedEventV1 feedback =
            M4BoundedEventV1.newLocalControlEvent();
        String bottleId = String.format("Q100-B%03d", number);
        controller.setRatio(75);
        require(controller.acceptFillADone(
            bottleId + "|L|500|GEOM_L|PACK_L|125", 0L
        ), "Filler B accepts " + bottleId);

        long blackoutStart = -1L;
        for (long now = 0L; now <= 4000L; now += 10L) {
            controller.tick(now);
            plant.tick(now);

            String value = controller.takePlantCommand();
            if (value != null) { commands.publish(value, now); }
            boolean blackedOut = pauseAtSafeWait && blackoutStart >= 0L &&
                now < blackoutStart + 500L;
            value = commands.take(now);
            if (value != null && !blackedOut) {
                plant.acceptCommand(value, now);
            }

            value = plant.takeFeedback();
            if (value != null) { feedback.publish(value, now); }
            value = feedback.take(now);
            if (value != null && !blackedOut) {
                controller.acceptPlantFeedback(value, now);
            }
            if (pauseAtSafeWait && blackoutStart < 0L &&
                "SAFE_WAIT".equals(controller.getStageName())) {
                blackoutStart = now + 10L;
            }
            if (controller.getStatus() == M4StatusV1.DONE) {
                return bottleId.equals(controller.takeCompletion());
            }
            if (controller.getStatus() == M4StatusV1.FAULT) {
                return false;
            }
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
