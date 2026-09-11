/** Replays actual sparse GUI telemetry from the three-large-bottle integration run. */
public final class ABSVisualisationLiveTraceSelfTest {
    private static final String[] TRACE = {
        "1788967772103,SORT_PACK_STATUS,1",
        "1788967773008,REQUIRED_BOTTLES,0",
        "1788967773019,COMPLETED_BOTTLES,0",
        "1788967774014,LOADER_STATUS,1",
        "1788967774014,CONVEYOR_STATUS,1",
        "1788967774014,ROTARY_STATUS,1",
        "1788967774014,LABELLER_STATUS,1",
        "1788967774017,FILLER_A_STATUS,1",
        "1788967774017,LID_STATUS,1",
        "1788967775016,FILLER_B_STATUS,1",
        "1788967775016,CAPPER_STATUS,1",
        "1788967775020,UNLOADER_STATUS,1",
        "1788967781497,REQUIRED_BOTTLES,3",
        "1788967783021,FILLER_A_STATUS,2",
        "1788967783024,ROTARY_STATUS,3",
        "1788967784020,ROTARY_STATUS,2",
        "1788967784020,FILLER_A_STATUS,3",
        "1788967784020,FILLER_B_STATUS,3",
        "1788967785019,ROTARY_STATUS,3",
        "1788967785019,FILLER_A_STATUS,2",
        "1788967785019,LID_STATUS,2",
        "1788967786018,LOADER_STATUS,3",
        "1788967786018,ROTARY_STATUS,2",
        "1788967786018,FILLER_A_STATUS,3",
        "1788967786021,LID_STATUS,1",
        "1788967787019,ROTARY_STATUS,3",
        "1788967787021,FILLER_B_STATUS,2",
        "1788967787022,CAPPER_STATUS,2",
        "1788967788021,FILLER_B_STATUS,3",
        "1788967788021,LID_STATUS,2",
        "1788967789021,ROTARY_STATUS,2",
        "1788967789024,LID_STATUS,1",
        "1788967789446,COMPLETED_BOTTLES,1",
        "1788967789457,SORT_PACK_STATUS,2",
        "1788967789679,SORT_PACK_STATUS,3",
        "1788967790033,ROTARY_STATUS,3",
        "1788967791034,COMPLETED_BOTTLES,2",
        "1788967791048,CAPPER_STATUS,3",
        "1788967791051,SORT_PACK_STATUS,2",
        "1788967791263,SORT_PACK_STATUS,3",
        "1788967792023,LABELLER_STATUS,3",
        "1788967792338,COMPLETED_BOTTLES,3",
        "1788967792346,SORT_PACK_STATUS,2",
        "1788967792563,SORT_PACK_STATUS,3",
        "1788967793345,LABELLER_STATUS,1"
    };
    private static final String[] STATUS_NAMES = {
        "LOADER_STATUS", "CONVEYOR_STATUS", "ROTARY_STATUS",
        "FILLER_A_STATUS", "FILLER_B_STATUS", "LID_STATUS",
        "CAPPER_STATUS", "LABELLER_STATUS", "UNLOADER_STATUS",
        "SORT_PACK_STATUS"
    };

    public static void main(String[] args) {
        ABSVisualisationFlowModel model = new ABSVisualisationFlowModel();
        long now = Long.parseLong(TRACE[0].split(",")[0]);
        model.tickElapsed(now * 1000000L);
        for (String event : TRACE) {
            String[] parts = event.split(",");
            long at = Long.parseLong(parts[0]);
            while (now + 30L <= at) {
                now += 30L;
                model.tickElapsed(now * 1000000L);
                if (!model.invariantsHold()) throw new AssertionError("Replay invariant");
            }
            int value = Integer.parseInt(parts[2]);
            if ("REQUIRED_BOTTLES".equals(parts[1])) model.acceptRequired(value);
            else if ("COMPLETED_BOTTLES".equals(parts[1])) model.acceptCompleted(value);
            else {
                for (int index = 0; index < STATUS_NAMES.length; index++)
                    if (STATUS_NAMES[index].equals(parts[1])) model.acceptStatus(index, value);
            }
        }
        // The real GUI run provided another eighteen seconds after the final
        // status. That is sufficient when its Swing timer continues ticking.
        int drainTicks = args.length == 0 ? 600 : Integer.parseInt(args[0]);
        for (int tick = 0; tick < drainTicks; tick++) {
            now += 30L;
            model.tickElapsed(now * 1000000L);
            if (!model.invariantsHold()) throw new AssertionError("Catch-up invariant");
        }
        if (model.getVisualCompleted() != 3) {
            for (int index = 0; index < ABSVisualisationFlowModel.MODULE_COUNT; index++) {
                ABSVisualisationFlowModel.ModuleSnapshot module = model.getModuleSnapshot(index);
                System.out.println("module=" + index + " started=" + model.getStartedCycles(index) +
                    " claimed=" + model.getClaimedCycles(index) + " bottle=" + module.getCurrentBottleId() +
                    " progress=" + module.getProgress() + " phase=" + module.getPhase());
            }
            throw new AssertionError("All three independently sorted bottles should converge: " +
                model.getVisualCompleted());
        }
        System.out.println("ABSVisualisationLiveTraceSelfTest PASS");
    }
}
