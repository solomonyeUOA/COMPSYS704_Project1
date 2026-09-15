/** Local retry adapter. It samples Plant evidence outside the controller lock. */
public final class M3PickRecoveryV1 {
    private static String key = "";
    private static String event, epoch, bottle;
    private static long placements;
    private static boolean started;
    private static volatile String status = "No active pick recovery";
    private M3PickRecoveryV1() { }

    public static String status() { return status; }

    public static synchronized void tick() {
        if (!"LID".equals(FaultSupervisorStateV2_1.activeSubsystem()) ||
            !"PICK_TIMEOUT".equals(FaultSupervisorStateV2_1.activeFaultCode())) {
            key = "";
            started = false;
            return;
        }
        String currentEvent = FaultSupervisorStateV2_1.activeEventId();
        String currentEpoch = FaultSupervisorStateV2_1.activeEpoch();
        String[] controller = Member3MachineStateV1.pickRecoveryState();
        if (!(currentEpoch + "/" + currentEvent).equals(key)) {
            key = currentEpoch + "/" + currentEvent;
            event = currentEvent;
            epoch = currentEpoch;
            bottle = controller[1];
            started = false;
            status = "Checking original bottle, home, empty gripper and available drives";
        }
        String state = FaultSupervisorStateV2_1.stateName();
        if ("WAITING_ACK".equals(state) && !started) {
            PlantEvidence evidence = Member3PlantStateV1.pickRecoveryEvidence(bottle);
            String rejection = !SystemWatchdogV1.isDriveFailoverEnabled() ? "WATCHDOG_DISABLED" :
                !event.equals(controller[0]) || !"PICK_TIMEOUT".equals(controller[3]) ? "CONTROLLER_EVENT_MISMATCH" :
                !evidence.matching ? "ORIGINAL_BOTTLE_NOT_AT_LID_STATION" :
                !evidence.home ? "ACTUATOR_NOT_HOME" : !evidence.empty ? "LID_ALREADY_HELD" :
                !evidence.available ? "NO_LID_AVAILABLE" : !evidence.drives ? "DRIVE_UNAVAILABLE" : null;
            if (rejection != null) {
                FaultSupervisorStateV2_1.localPickAck(event, epoch, false, rejection);
                notice("SAFE_ERROR", "No executable retry: " + rejection, true);
                return;
            }
            placements = evidence.placements;
            if (!FaultSupervisorStateV2_1.localPickAck(event, epoch, true, "PLANT_INTERLOCKS_VERIFIED")) return;
            started = Member3MachineStateV1.startPickRetry(event, epoch, bottle);
            if (!started) {
                FaultSupervisorStateV2_1.localPickResult(event, epoch, false);
                notice("SAFE_ERROR", "Controller refused correlated retry", true);
            } else notice("TRANSFER_PHASE", "Automatic pick retry 1/1; original bottle=" + bottle, false);
        } else if ("WAITING_RESULT".equals(state) && started) {
            PlantEvidence evidence = Member3PlantStateV1.pickRecoveryEvidence(bottle);
            boolean matching = event.equals(controller[0]) && bottle.equals(controller[1]);
            if (!SystemWatchdogV1.isDriveFailoverEnabled() || !matching || "FAULT".equals(controller[2])) {
                FaultSupervisorStateV2_1.localPickResult(event, epoch, false);
                notice("SAFE_ERROR", "Pick retry failed or interrupted; no further attempt", true);
            } else if ("DONE".equals(controller[2])) {
                boolean verified = evidence.matching && evidence.home && evidence.empty && evidence.drives &&
                    evidence.placements == placements + 1;
                boolean accepted = FaultSupervisorStateV2_1.localPickResult(event, epoch, verified);
                notice(accepted && verified ? "TRANSFER_PHASE" : "SAFE_ERROR",
                    accepted && verified ? "Original bottle completed; awaiting automatic M1 Resume" :
                    "Retry completion evidence rejected", !accepted || !verified);
            }
        } else if ("LOCKED_OUT".equals(state) || "FAILED".equals(state)) {
            status = "Safe hold; retry unavailable or failed: " + FaultSupervisorStateV2_1.decision();
        }
    }

    private static void notice(String action, String detail, boolean failed) {
        status = detail;
        DriveEventsV1.record("LID PICK RECOVERY", action, 1, detail + " event=" + key, failed);
    }

    public static final class PlantEvidence {
        final boolean matching, home, empty, available, drives;
        final long placements;
        PlantEvidence(boolean matching, boolean home, boolean empty, boolean available, boolean drives, long placements) {
            this.matching = matching; this.home = home; this.empty = empty;
            this.available = available; this.drives = drives; this.placements = placements;
        }
    }
}
