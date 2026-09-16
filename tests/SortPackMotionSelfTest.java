/**
 * Display-only Sort / Pack easing: it must move the picture between confirmed
 * twin waypoints without ever inventing a sort that the twin has not confirmed.
 */
public final class SortPackMotionSelfTest {
    private static final double DIVERTER = SortPackMotionModel.DIVERTER_FRACTION;
    private static final double EPSILON = 0.000001;
    private static int assertions;

    private SortPackMotionSelfTest() {
    }

    public static void main(String[] args) {
        noBottleNeverMoves();
        busyWalksTheInfeedAndStopsAtTheDiverter();
        confirmationRunsTheBranchIntoThePackage();
        idleHoldsAndNothingEverMovesBackwards();
        anotherIdentityStartsItsOwnJourney();
        aStalledFrameCannotTeleport();
        confirmationWithoutAnObservedBusyStillWalksTheInfeed();
        rejectsImpossiblePacing();
        defaultPacingFollowsSimulationTiming();
        System.out.println("SortPackMotionSelfTest PASS (" + assertions +
            " assertions, slowdown=" + SimulationTiming.slowdown() + ")");
    }

    private static void noBottleNeverMoves() {
        SortPackMotionModel motion = new SortPackMotionModel(100L, 100L);
        require(motion.observe("", true, true, 0L) == 0.0, "no identity, no motion");
        require(motion.observe(null, true, true, 5000L) == 0.0,
            "a missing identity cannot accumulate elapsed time");
        require(motion.getFraction() == 0.0, "idle schematic stays at the infeed");
    }

    private static void busyWalksTheInfeedAndStopsAtTheDiverter() {
        SortPackMotionModel motion = new SortPackMotionModel(100L, 100L);
        motion.observe("PO1-B1", true, false, 0L);
        require(near(motion.observe("PO1-B1", true, false, 50L), DIVERTER / 2.0),
            "half the route delay walks half the infeed");
        require(near(motion.observe("PO1-B1", true, false, 100L), DIVERTER),
            "the infeed run completes in one scaled route delay");
        for (long now = 200L; now <= 10000L; now += 100L) {
            require(near(motion.observe("PO1-B1", true, false, now), DIVERTER),
                "BUSY alone can never reach the package at " + now + "ms");
        }
    }

    private static void confirmationRunsTheBranchIntoThePackage() {
        SortPackMotionModel motion = new SortPackMotionModel(100L, 100L);
        motion.observe("PO1-B1", true, false, 0L);
        motion.observe("PO1-B1", true, false, 100L);
        require(near(motion.getFraction(), DIVERTER), "waiting at the diverter");
        double half = motion.observe("PO1-B1", false, true, 150L);
        require(half > DIVERTER && half < 1.0 - EPSILON,
            "confirmation starts the branch without completing it");
        require(near(motion.observe("PO1-B1", false, true, 200L), 1.0),
            "the branch completes in one scaled place delay");
        require(motion.observe("PO1-B1", false, true, 4000L) == 1.0,
            "a placed bottle stays placed");
    }

    private static void idleHoldsAndNothingEverMovesBackwards() {
        SortPackMotionModel motion = new SortPackMotionModel(100L, 100L);
        motion.observe("PO1-B1", true, false, 0L);
        double held = motion.observe("PO1-B1", true, false, 40L);
        require(held > 0.0, "the infeed has started");
        require(motion.observe("PO1-B1", false, false, 90L) == held,
            "neither busy nor confirmed holds the last drawn position");
        double previous = held;
        for (long now = 100L; now <= 600L; now += 20L) {
            boolean busy = now % 40L == 0L;
            double current = motion.observe("PO1-B1", busy, false, now);
            require(current + EPSILON >= previous, "no backward motion at " + now + "ms");
            previous = current;
        }
    }

    private static void anotherIdentityStartsItsOwnJourney() {
        SortPackMotionModel motion = new SortPackMotionModel(100L, 100L);
        motion.observe("PO1-B1", true, false, 0L);
        motion.observe("PO1-B1", true, false, 100L);
        motion.observe("PO1-B1", false, true, 200L);
        require(near(motion.getFraction(), 1.0), "first bottle is placed");
        require(motion.observe("PO1-B2", true, false, 210L) == 0.0,
            "a new identity cannot inherit the placed position");
        require(motion.observe("PO1-B2", true, false, 260L) > 0.0,
            "the new identity walks its own infeed");
        motion.reset();
        require(motion.getFraction() == 0.0, "reset clears the picture");
    }

    private static void aStalledFrameCannotTeleport() {
        SortPackMotionModel motion = new SortPackMotionModel(1000L, 1000L);
        motion.observe("PO1-B1", true, false, 0L);
        double afterStall = motion.observe("PO1-B1", true, false, 10000L);
        require(afterStall < DIVERTER / 2.0 && afterStall > 0.0,
            "a ten second stall advances at most one bounded frame");
    }

    private static void confirmationWithoutAnObservedBusyStillWalksTheInfeed() {
        // A one second twin poll can miss a short BUSY window entirely.
        SortPackMotionModel motion = new SortPackMotionModel(100L, 100L);
        require(motion.observe("PO1-B1", false, true, 0L) == 0.0, "first frame has no elapsed time");
        double first = motion.observe("PO1-B1", false, true, 30L);
        require(first > 0.0 && first < DIVERTER,
            "a confirmed sort starts at the infeed instead of jumping to the package");
        require(near(motion.observe("PO1-B1", false, true, 100L), DIVERTER),
            "the infeed is walked before the branch begins");
        require(near(motion.observe("PO1-B1", false, true, 200L), 1.0),
            "the branch still completes after the caught-up infeed");
    }

    private static void rejectsImpossiblePacing() {
        for (long[] pacing : new long[][] {{0L, 100L}, {100L, 0L}, {-1L, 100L}}) {
            try {
                new SortPackMotionModel(pacing[0], pacing[1]);
                throw new AssertionError("accepted " + pacing[0] + "/" + pacing[1]);
            }
            catch (IllegalArgumentException expected) {
                assertions++;
            }
        }
    }

    private static void defaultPacingFollowsSimulationTiming() {
        SortPackMotionModel motion = new SortPackMotionModel();
        long route = SimulationTiming.scaleMillis(100L);
        // Repaint frames, so the bounded-frame clamp applies exactly as it does live.
        long half = route / 40L * 20L;
        motion.observe("PO1-B1", true, false, 0L);
        for (long now = 20L; now <= half; now += 20L) {
            motion.observe("PO1-B1", true, false, now);
        }
        require(motion.getFraction() < DIVERTER,
            "demo slowdown stretches the drawn infeed with the simulated route");
        for (long now = half + 20L; now <= route + 40L; now += 20L) {
            motion.observe("PO1-B1", true, false, now);
        }
        require(near(motion.getFraction(), DIVERTER),
            "the drawn infeed still ends exactly at the scaled route delay");
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < EPSILON;
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
