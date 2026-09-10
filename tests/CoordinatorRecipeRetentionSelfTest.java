/**
 * Generated-Coordinator regression for level-held active-order recipes.
 *
 * Run after coordinator.sysj has been generated and compiled. This exercises
 * actual FILL_A_RATIO/FILL_B_RATIO Signal presence across logical reactions,
 * independently of the one-second status poll.
 */
public final class CoordinatorRecipeRetentionSelfTest {
    private CoordinatorRecipeRetentionSelfTest() {
    }

    public static void main(String[] args) {
        CoordinatorStateV1.nextStatusPollMillis = Long.MAX_VALUE;
        require(
            CoordinatorStateV1.accept("PO-Q1|1|P1,25,75,1"),
            "quantity=1 order must be accepted"
        );

        Coordinator coordinator = new Coordinator("RecipeRetentionProbe");
        coordinator.init();

        assertRecipeForReactions(
            coordinator,
            25,
            75,
            5,
            "quantity=1 delayed reader"
        );
        require(
            CoordinatorStateV1.recordBottleDone(),
            "quantity=1 must complete on its first bottle"
        );
        assertRecipeForReactions(
            coordinator,
            25,
            75,
            2,
            "recipe must remain until completion state is cleared"
        );
        CoordinatorStateV1.completeOrder();
        assertRecipeAbsent(
            coordinator,
            3,
            "quantity=1 recipe must stop after completeOrder"
        );

        require(
            CoordinatorStateV1.accept("PO-Q3|1|P2,60,40,3"),
            "quantity>1 order must be accepted"
        );
        assertRecipeForReactions(
            coordinator,
            60,
            40,
            7,
            "quantity>1 delayed reader"
        );

        require(
            !CoordinatorStateV1.recordBottleDone(),
            "quantity=3 must not complete after bottle 1"
        );
        assertRecipeForReactions(
            coordinator,
            60,
            40,
            2,
            "quantity=3 recipe after bottle 1"
        );
        require(
            !CoordinatorStateV1.recordBottleDone(),
            "quantity=3 must not complete after bottle 2"
        );
        assertRecipeForReactions(
            coordinator,
            60,
            40,
            2,
            "quantity=3 recipe after bottle 2"
        );
        require(
            CoordinatorStateV1.recordBottleDone(),
            "quantity=3 must complete exactly after bottle 3"
        );
        require(
            CoordinatorStateV1.completedBottles == 3,
            "quantity=3 completion count must be exact"
        );
        CoordinatorStateV1.completeOrder();
        String completionPayload =
            CoordinatorStateV1.pendingCompletionPayload;
        require(
            completionPayload.startsWith("PO-Q3|COMPLETED|"),
            "completion payload must belong to the active quantity=3 order"
        );
        require(
            !CoordinatorStateV1.recordBottleDone() &&
            CoordinatorStateV1.completedBottles == 3,
            "a late bottle event must not duplicate completion or count"
        );
        assertRecipeAbsent(
            coordinator,
            3,
            "quantity=3 recipe must stop after completeOrder"
        );

        System.out.println(
            "CoordinatorRecipeRetentionSelfTest PASSED " +
            "q1DelayedReactions=5 q3DelayedReactions=11"
        );
    }

    private static void assertRecipeForReactions(
        Coordinator coordinator,
        int expectedA,
        int expectedB,
        int reactions,
        String context
    ) {
        for (int reaction = 1; reaction <= reactions; reaction++) {
            clearRecipeSignals(coordinator);
            coordinator.runClockDomain();
            require(
                coordinator.FILL_A_RATIO.getStatus(),
                context + ": FILL_A_RATIO absent at reaction " + reaction
            );
            require(
                coordinator.FILL_B_RATIO.getStatus(),
                context + ": FILL_B_RATIO absent at reaction " + reaction
            );
            require(
                Integer.valueOf(expectedA).equals(
                    coordinator.FILL_A_RATIO.getValue()
                ),
                context + ": wrong A ratio at reaction " + reaction
            );
            require(
                Integer.valueOf(expectedB).equals(
                    coordinator.FILL_B_RATIO.getValue()
                ),
                context + ": wrong B ratio at reaction " + reaction
            );
        }
    }

    private static void assertRecipeAbsent(
        Coordinator coordinator,
        int reactions,
        String context
    ) {
        for (int reaction = 1; reaction <= reactions; reaction++) {
            clearRecipeSignals(coordinator);
            coordinator.runClockDomain();
            require(
                !coordinator.FILL_A_RATIO.getStatus() &&
                !coordinator.FILL_B_RATIO.getStatus(),
                context + ": recipe remained present at reaction " +
                reaction
            );
        }
    }

    private static void clearRecipeSignals(Coordinator coordinator) {
        coordinator.FILL_A_RATIO.setClear();
        coordinator.FILL_B_RATIO.setClear();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
