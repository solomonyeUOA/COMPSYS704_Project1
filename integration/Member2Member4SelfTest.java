/** Checks retained M2 Sort/Pack hand-offs against the real M4 receiver. */
public final class Member2Member4SelfTest {
    public static void main(String[] args) {
        runScenario(1);
        runScenario(3);
        System.out.println("Member2Member4SelfTest PASSED (q1 and q3)");
    }

    private static void runScenario(int quantity) {
        BottleUnloaderControllerModelV1 unloader =
            new BottleUnloaderControllerModelV1(10L);
        SortPackControllerModelV1 sortPack =
            new SortPackControllerModelV1(2, 2, 2500L);

        for (int index = 1; index <= quantity; index++) {
            String bottleId = "Q" + quantity + "B00" + index;
            boolean small = index % 2 == 1;
            String context = bottleId + (small ?
                "|S|200|GEOM_S|PACK_S" :
                "|L|500|GEOM_L|PACK_L");
            String lane = small ? "LANE_S" : "LANE_L";
            String pack = small ? "PACK_S" : "PACK_L";
            long cycleStart = index * 1000L;

            check(unloader.acceptProfile(context),
                "M2 accepts M4 profile " + bottleId);
            check(unloader.acceptUnloadReady(bottleId),
                "M2 receives label permission " + bottleId);
            check(bottleId.equals(unloader.takeUnloadCommand()),
                "M2 starts verified unload " + bottleId);
            check(unloader.acceptRemovalConfirmed(
                bottleId + "|true", cycleStart
            ), "M2 confirms physical removal " + bottleId);
            check(bottleId.equals(unloader.takeP6Clear()),
                "M2 retains matching P6 identity " + bottleId);

            String sortPayload = unloader.takeSortContext();
            check(context.equals(sortPayload),
                "M2 preserves M4 context " + bottleId);
            M2BoundedSignalOfferV1 offer =
                new M2BoundedSignalOfferV1(3, 500L, 100L);
            check(offer.arm(bottleId, sortPayload, 0L),
                "BOTTLE_READY_FOR_SORT arms " + bottleId);
            check(sortPayload.equals(offer.nextReactionValue(0L)),
                "first PRESENT is available but simulated lost");
            check(offer.nextReactionValue(500L) == null,
                "retry is separated by ABSENT gap");

            String retry = offer.nextReactionValue(600L);
            check(sortPack.acceptBottleReady(retry, cycleStart + 600L),
                "M4 accepts retained retry " + bottleId);
            check((bottleId + "|SET_LANE|" + lane).equals(
                sortPack.takePlantCommand()),
                "one route command generated " + bottleId);
            check(!sortPack.acceptBottleReady(
                offer.nextReactionValue(601L), cycleStart + 601L
            ), "M4 de-duplicates repeated PRESENT " + bottleId);
            check(sortPack.takePlantCommand() == null,
                "duplicate creates no physical command " + bottleId);

            sortPack.acceptPlantFeedback(
                bottleId + "|LANE_CONFIRMED|" + lane,
                cycleStart + 700L
            );
            check((bottleId + "|PLACE|" + pack).equals(
                sortPack.takePlantCommand()),
                "M4 places the accepted bottle once " + bottleId);
            sortPack.acceptPlantFeedback(
                bottleId + "|PLACED|" + pack,
                cycleStart + 800L
            );
            check((bottleId + "|SORT_PACK_COMPLETE|" + pack).equals(
                sortPack.takeCompletion()),
                "M4 completes accepted bottle " + bottleId);

            check(offer.nextReactionValue(1100L) == null,
                "second window ends with ABSENT");
            check(!sortPack.acceptBottleReady(
                offer.nextReactionValue(1200L), cycleStart + 1200L
            ), "late copy cannot restart completed bottle " + bottleId);
            check(sortPack.takePlantCommand() == null,
                "late duplicate causes no work " + bottleId);

            check(unloader.isBottleDonePresent(cycleStart),
                "BOTTLE_DONE PRESENT " + bottleId);
            check(!unloader.isBottleDonePresent(cycleStart + 11L),
                "BOTTLE_DONE becomes ABSENT " + bottleId);
            check(!unloader.isBottleDonePresent(cycleStart + 12L),
                "ABSENT gap re-arms Unloader " + bottleId);
            check(unloader.getStatus() == M2StatusV1.READY,
                "Unloader ready for next q" + quantity + " bottle");
        }

        check(sortPack.getSmallBottleCount() == (quantity + 1) / 2,
            "q" + quantity + " small-bottle total");
        check(sortPack.getLargeBottleCount() == quantity / 2,
            "q" + quantity + " large-bottle total");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
