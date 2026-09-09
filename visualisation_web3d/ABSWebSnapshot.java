import java.util.ArrayList;
import java.util.List;

/** Immutable JSON-ready projection of the validated M1 visualisation models. */
final class ABSWebSnapshot {
    private static final String[] MACHINE_KEYS = {
        "loader", "conveyor", "rotary", "fillerA",
        "fillerB", "lid", "capper", "unloader"
    };
    private static final String[] MACHINE_NAMES = {
        "Loader", "Conveyor", "Rotary", "Filler A",
        "Filler B", "Lid", "Capper", "Unloader"
    };

    private final long timestamp;
    private final boolean demoMode;
    private final int requiredBottles;
    private final int completedBottles;
    private final int visualCompletedBottles;
    private final String flowMode;
    private final String ftEvidence;
    private final MachineView[] machines;
    private final List<BottleView> visualBottles;
    private final TeamIpView[] teamIp;

    private ABSWebSnapshot(
        long capturedAt,
        boolean demo,
        int required,
        int completed,
        int visualCompleted,
        String mode,
        String latestFtEvidence,
        MachineView[] machineViews,
        List<BottleView> bottles,
        TeamIpView[] extensions
    ) {
        timestamp = capturedAt;
        demoMode = demo;
        requiredBottles = required;
        completedBottles = completed;
        visualCompletedBottles = visualCompleted;
        flowMode = mode;
        ftEvidence = latestFtEvidence;
        machines = machineViews.clone();
        visualBottles = new ArrayList<BottleView>(bottles);
        teamIp = extensions.clone();
    }

    static ABSWebSnapshot capture(
        ABSVisualisationFlowModel.FlowSnapshot flow,
        ABSVisualisationTeamIpModel.Snapshot extensions,
        int[] statuses,
        boolean[] received,
        boolean demo,
        String latestFtEvidence
    ) {
        MachineView[] machineViews = new MachineView[
            ABSVisualisationFlowModel.MODULE_COUNT
        ];
        for (int index = 0; index < machineViews.length; index++) {
            machineViews[index] = new MachineView(
                index,
                statuses[index],
                received[index],
                flow.getModule(index)
            );
        }

        List<BottleView> bottles = new ArrayList<BottleView>();
        for (ABSVisualisationFlowModel.BottleSnapshot bottle :
            flow.getBottles()) {
            bottles.add(new BottleView(bottle));
        }

        TeamIpView[] teamViews = new TeamIpView[
            ABSVisualisationTeamIpModel.EXTENSION_COUNT
        ];
        for (int index = 0; index < teamViews.length; index++) {
            teamViews[index] = new TeamIpView(
                extensions.getExtension(index)
            );
        }

        return new ABSWebSnapshot(
            System.currentTimeMillis(),
            demo,
            flow.getRequired(),
            flow.getRealCompleted(),
            flow.getVisualCompleted(),
            flow.getMode(),
            latestFtEvidence == null ? "" : latestFtEvidence,
            machineViews,
            bottles,
            teamViews
        );
    }

    String toJson() {
        StringBuilder json = new StringBuilder(4096);
        json.append('{');
        numberField(json, "timestamp", timestamp);
        json.append(',');
        booleanField(json, "readOnly", true);
        json.append(',');
        booleanField(json, "demoMode", demoMode);
        json.append(',');
        stringField(
            json,
            "telemetrySource",
            demoMode ? "DEMO / TEST ONLY" : "EXPERIMENTAL M1 WEB BRIDGE"
        );
        json.append(',');
        numberField(json, "requiredBottles", requiredBottles);
        json.append(',');
        numberField(json, "completedBottles", completedBottles);
        json.append(',');
        numberField(
            json,
            "visualCompletedBottles",
            visualCompletedBottles
        );
        json.append(',');
        stringField(json, "flowMode", flowMode);
        json.append(',');
        stringField(json, "ftEvidence", ftEvidence);
        json.append(',');
        json.append("\"machines\":[");
        for (int index = 0; index < machines.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            machines[index].appendJson(json);
        }
        json.append("],\"visualBottles\":[");
        for (int index = 0; index < visualBottles.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            visualBottles.get(index).appendJson(json);
        }
        json.append("],\"teamIp\":[");
        for (int index = 0; index < teamIp.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            teamIp[index].appendJson(json);
        }
        return json.append("]}").toString();
    }

    private static final class MachineView {
        private final int index;
        private final int status;
        private final boolean received;
        private final ABSVisualisationFlowModel.ModuleSnapshot module;

        MachineView(
            int machineIndex,
            int controllerStatus,
            boolean hasStatus,
            ABSVisualisationFlowModel.ModuleSnapshot snapshot
        ) {
            index = machineIndex;
            status = controllerStatus;
            received = hasStatus;
            module = snapshot;
        }

        void appendJson(StringBuilder json) {
            json.append('{');
            numberField(json, "index", index);
            json.append(',');
            stringField(json, "key", MACHINE_KEYS[index]);
            json.append(',');
            stringField(json, "name", MACHINE_NAMES[index]);
            json.append(',');
            numberField(json, "status", received ? status : -1);
            json.append(',');
            stringField(
                json,
                "statusName",
                received ? statusName(status) : "WAITING"
            );
            json.append(',');
            booleanField(json, "statusReceived", received);
            json.append(',');
            stringField(json, "lifecycle", module.getLifecycle().name());
            json.append(',');
            stringField(json, "phase", module.getPhase());
            json.append(',');
            decimalField(json, "progress", module.getProgress());
            json.append(',');
            booleanField(json, "running", module.isRunning());
            json.append(',');
            decimalField(
                json,
                "conveyorBottlePosition",
                module.getConveyorBottlePosition()
            );
            json.append(',');
            decimalField(json, "rollerAngle", module.getRollerAngle());
            json.append(',');
            decimalField(json, "rotaryAngle", module.getRotaryAngle());
            json.append(',');
            decimalField(
                json,
                "rotaryEntryProgress",
                module.getRotaryEntryProgress()
            );
            json.append(',');
            decimalField(
                json,
                "rotaryExitProgress",
                module.getRotaryExitProgress()
            );
            json.append(',');
            stringField(json, "rotaryPhase", module.getRotaryPhase());
            json.append(',');
            json.append("\"rotaryPositions\":[");
            for (int station = 0;
                station < module.getRotaryStationCount();
                station++) {
                if (station > 0) {
                    json.append(',');
                }
                json.append(module.isRotaryStationOccupied(station));
            }
            json.append(']');
            json.append(',');
            decimalField(json, "liquidA", module.getLiquidALevel());
            json.append(',');
            decimalField(json, "liquidB", module.getLiquidBLevel());
            json.append(',');
            decimalField(
                json,
                "tighteningAngle",
                module.getTighteningAngle()
            );
            json.append('}');
        }
    }

    /** Symbolic rendering record; deliberately exposes no bottle identity. */
    private static final class BottleView {
        private final int stage;
        private final double progress;
        private final String lifecycle;

        BottleView(ABSVisualisationFlowModel.BottleSnapshot snapshot) {
            stage = snapshot.getStage();
            progress = snapshot.getProgress();
            lifecycle = snapshot.getLifecycle().name();
        }

        void appendJson(StringBuilder json) {
            json.append('{');
            numberField(json, "stage", stage);
            json.append(',');
            decimalField(json, "progress", progress);
            json.append(',');
            stringField(json, "lifecycle", lifecycle);
            json.append('}');
        }
    }

    private static final class TeamIpView {
        private final ABSVisualisationTeamIpModel.ExtensionSnapshot extension;

        TeamIpView(ABSVisualisationTeamIpModel.ExtensionSnapshot value) {
            extension = value;
        }

        void appendJson(StringBuilder json) {
            json.append('{');
            stringField(json, "member", extension.getMember());
            json.append(',');
            stringField(json, "title", extension.getTitle());
            json.append(',');
            stringField(json, "owner", extension.getOwner());
            json.append(',');
            stringField(json, "mode", extension.getMode());
            json.append(',');
            stringField(json, "summary", extension.getSummary());
            json.append(',');
            booleanField(
                json,
                "liveEvidenceAvailable",
                extension.isLiveEvidenceAvailable()
            );
            json.append(',');
            stringField(
                json,
                "liveHeadline",
                extension.getLiveHeadline()
            );
            json.append(',');
            stringField(
                json,
                "m1Representation",
                extension.getM1Representation()
            );
            json.append(',');
            stringArrayField(
                json,
                "architectureNodes",
                extension.getArchitectureNodes()
            );
            json.append(',');
            stringArrayField(
                json,
                "capabilityLines",
                extension.getCapabilityLines()
            );
            json.append(',');
            stringArrayField(json, "liveLines", extension.getLiveLines());
            json.append('}');
        }
    }

    private static String statusName(int status) {
        switch (status) {
            case 0:
                return "IDLE";
            case 1:
                return "READY";
            case 2:
                return "BUSY";
            case 3:
                return "DONE";
            case 4:
                return "FAULT";
            default:
                return "UNKNOWN";
        }
    }

    private static void stringArrayField(
        StringBuilder json,
        String name,
        String[] values
    ) {
        json.append(quote(name)).append(":[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(quote(values[index]));
        }
        json.append(']');
    }

    private static void stringField(
        StringBuilder json,
        String name,
        String value
    ) {
        json.append(quote(name)).append(':').append(quote(value));
    }

    private static void numberField(
        StringBuilder json,
        String name,
        long value
    ) {
        json.append(quote(name)).append(':').append(value);
    }

    private static void decimalField(
        StringBuilder json,
        String name,
        double value
    ) {
        double finite = Double.isNaN(value) || Double.isInfinite(value) ?
            0.0 : value;
        json.append(quote(name)).append(':').append(
            String.format(java.util.Locale.ROOT, "%.3f", finite)
        );
    }

    private static void booleanField(
        StringBuilder json,
        String name,
        boolean value
    ) {
        json.append(quote(name)).append(':').append(value);
    }

    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        escaped.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\':
                case '"':
                    escaped.append('\\').append(character);
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format(
                            java.util.Locale.ROOT,
                            "\\u%04x",
                            Integer.valueOf(character)
                        ));
                    }
                    else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.append('"').toString();
    }
}
