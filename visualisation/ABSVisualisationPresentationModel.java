import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Read-only presentation projection shared by the overview and module details.
 *
 * This model does not advance production. It only joins immutable flow, Twin
 * and FT snapshots with the latest received Controller status evidence.
 */
final class ABSVisualisationPresentationModel {
    static final int MODULE_COUNT = 10;
    private static final int HISTORY_LIMIT = 6;
    private static final String[] MODULE_TOKENS = {
        "loader", "conveyor", "rotary", "filler a", "filler b",
        "lid", "capper", "labeller", "unloader", "sort"
    };
    private static final String[][] MODULE_STAGES = {
        {"CREATED", "LOADED"},
        {"LOADED", "P1"},
        {"P1"},
        {"P1", "FILLED"},
        {"P1", "FILLED"},
        {"FILLED", "LIDDED"},
        {"LIDDED", "CAPPED"},
        {"P6", "LABELLED"},
        {"LABELLED", "UNLOADED"},
        {"UNLOADED", "SORTED", "COMPLETE"}
    };

    static final class ModuleContext {
        private final String bottleId;
        private final String stage;
        private final String resource;
        private final String operation;
        private final String sizeCode;
        private final String capacity;
        private final boolean confirmed;
        private final List<String> history;

        ModuleContext(
            String bottle,
            String currentStage,
            String currentResource,
            String currentOperation,
            String size,
            String capacityMl,
            boolean liveConfirmed,
            List<String> recentHistory
        ) {
            bottleId = bottle;
            stage = currentStage;
            resource = currentResource;
            operation = currentOperation;
            sizeCode = size;
            capacity = capacityMl;
            confirmed = liveConfirmed;
            history = Collections.unmodifiableList(
                new ArrayList<String>(recentHistory)
            );
        }

        String getBottleId() { return bottleId; }
        String getStage() { return stage; }
        String getResource() { return resource; }
        String getOperation() { return operation; }
        String getSizeCode() { return sizeCode; }
        String getCapacity() { return capacity; }
        boolean isConfirmed() { return confirmed; }
        List<String> getHistory() { return history; }
    }

    /** Size evidence used only to choose presentation geometry. */
    static final class BottleSizeContext {
        private final String sizeCode;
        private final String capacity;
        private final boolean confirmed;
        private final String source;

        BottleSizeContext(
            String size,
            String capacityMl,
            boolean liveConfirmed,
            String evidenceSource
        ) {
            sizeCode = BottleVisualGeometry.isSupportedSize(size) ?
                size.toUpperCase() : "--";
            capacity = BottleVisualGeometry.isSupportedSize(size) ?
                capacityMl : "--";
            confirmed = liveConfirmed &&
                BottleVisualGeometry.isSupportedSize(size);
            source = evidenceSource;
        }

        String getSizeCode() { return sizeCode; }
        String getCapacity() { return capacity; }
        boolean isConfirmed() { return confirmed; }
        String getSource() { return source; }
    }

    static final class Snapshot {
        private final long version;
        private final String activeOrder;
        private final String bottleContext;
        private final String currentStage;
        private final String systemState;
        private final ModuleContext[] modules;
        private final String[][] workpieces;
        private final String activeSizeCode;
        private final String activeCapacity;
        private final ABSVisualisationFlowModel.FlowSnapshot flow;

        Snapshot(
            long snapshotVersion,
            String order,
            String bottle,
            String stage,
            String state,
            ModuleContext[] contexts,
            String[][] currentWorkpieces,
            String currentSizeCode,
            String currentCapacity,
            ABSVisualisationFlowModel.FlowSnapshot currentFlow
        ) {
            version = snapshotVersion;
            activeOrder = order;
            bottleContext = bottle;
            currentStage = stage;
            systemState = state;
            modules = contexts.clone();
            workpieces = copyRows(currentWorkpieces);
            activeSizeCode = currentSizeCode;
            activeCapacity = currentCapacity;
            flow = currentFlow;
        }

        long getVersion() { return version; }
        String getActiveOrder() { return activeOrder; }
        String getBottleContext() { return bottleContext; }
        String getCurrentStage() { return currentStage; }
        String getSystemState() { return systemState; }
        ModuleContext getModule(int index) {
            checkIndex(index);
            return modules[index];
        }

        BottleSizeContext getBottleSize(int module, int symbolicBottleId) {
            checkIndex(module);
            if (flow.isTwinDriven()) {
                String[] observed = findWorkpiece(workpieces,
                    flow.getModule(module).getCurrentBottleKey());
                return observed == null ? sizeContext("--", "--", false,
                    "NO CURRENT TWIN BOTTLE") : sizeContext(observed[4], observed[5],
                    true, "LIVE / CONFIRMED BOTTLE");
            }
            String[] exact = findSymbolicBottle(workpieces, symbolicBottleId);
            if (exact != null) {
                return sizeContext(exact[4], exact[5], true,
                    "LIVE / CONFIRMED BOTTLE");
            }
            ModuleContext moduleContext = modules[module];
            if (BottleVisualGeometry.isSupportedSize(
                moduleContext.getSizeCode()
            )) {
                return sizeContext(
                    moduleContext.getSizeCode(),
                    stripCapacity(moduleContext.getCapacity()),
                    moduleContext.isConfirmed(),
                    "LIVE / CONFIRMED MODULE"
                );
            }
            if (BottleVisualGeometry.isSupportedSize(activeSizeCode)) {
                return sizeContext(activeSizeCode, activeCapacity, true,
                    "CURRENT ORDER / CONFIRMED SIZE");
            }
            return sizeContext("--", "--", false,
                "CAPABILITY / SYMBOLIC");
        }

        BottleLabelVisuals.State getBottleLabel(int module, int symbolicBottleId,
                                                double progress) {
            checkIndex(module);
            if (flow.isTwinDriven()) {
                String key = flow.getModule(module).getCurrentBottleKey();
                String[] observed = findWorkpiece(workpieces, key);
                ModuleContext context = modules[module];
                return BottleLabelVisuals.resolve(module, observed != null, progress, true,
                    observed == null ? "--" : observed[1],
                    key.equals(context.getBottleId()) ? context.getOperation() : "--");
            }
            // The animation carries a sequence, not a full order/bottle ID.
            // If multiple orders expose that sequence, do not guess success.
            if (ambiguousBottleSequence(workpieces, symbolicBottleId)) {
                return BottleLabelVisuals.resolve(module, true, progress, true,
                    "--", "--");
            }
            String[] exact = findSymbolicBottle(workpieces, symbolicBottleId);
            ModuleContext context = modules[module];
            if (exact != null) {
                // Resource success only belongs to the same full bottle ID.
                String operation = exact[0].equals(context.getBottleId()) ?
                    context.getOperation() : "";
                return BottleLabelVisuals.resolve(module, true, progress, true,
                    exact[1], operation);
            }
            boolean identified = context.isConfirmed() &&
                !isUnknown(context.getBottleId());
            // Never apply another bottle's resource evidence to an active symbol.
            if (identified && symbolicBottleId > 0 &&
                bottleSequence(context.getBottleId()) != symbolicBottleId) {
                return BottleLabelVisuals.resolve(module, true, progress, true,
                    "--", "--");
            }
            return BottleLabelVisuals.resolve(module,
                symbolicBottleId > 0 || identified, progress, identified,
                context.getStage(), context.getOperation());
        }

        private static BottleSizeContext sizeContext(
            String size,
            String capacity,
            boolean confirmed,
            String source
        ) {
            return new BottleSizeContext(size, capacity, confirmed, source);
        }

        private static boolean ambiguousBottleSequence(String[][] rows, int id) {
            if (id <= 0) return false;
            String identity = null;
            for (String[] row : rows) {
                if (bottleSequence(row[0]) != id) continue;
                if (identity != null && !identity.equals(row[0])) return true;
                identity = row[0];
            }
            return false;
        }

        private static String[] findSymbolicBottle(
            String[][] rows,
            int symbolicBottleId
        ) {
            if (symbolicBottleId <= 0) return null;
            String[] selected = null;
            long selectedVersion = Long.MIN_VALUE;
            for (String[] row : rows) {
                if (bottleSequence(row[0]) != symbolicBottleId) continue;
                long candidate = numeric(row[3]);
                if (selected == null || candidate >= selectedVersion) {
                    selected = row;
                    selectedVersion = candidate;
                }
            }
            return selected;
        }

        private static String stripCapacity(String value) {
            return value == null ? "--" : value.replace(" mL", "");
        }
    }

    private final Deque<String>[] history;
    private final String[] lastTwinEvidence = new String[MODULE_COUNT];
    private long eventSequence;
    private long version;

    @SuppressWarnings("unchecked")
    ABSVisualisationPresentationModel() {
        history = (Deque<String>[])new Deque<?>[MODULE_COUNT];
        for (int index = 0; index < MODULE_COUNT; index++) {
            history[index] = new ArrayDeque<String>();
        }
    }

    synchronized void recordStatus(int module, String status) {
        checkIndex(module);
        addHistory(module, "LIVE Controller status -> " + safe(status));
    }

    synchronized void observeTwin(ABSLiveTwinModel.Snapshot twin) {
        if (twin == null) return;
        String[][] resources = twin.resources();
        for (int module = 0; module < MODULE_COUNT; module++) {
            String[] row = findResource(resources, module);
            if (row == null) continue;
            String evidence = row[0] + " | " + row[2] + " | " + row[3] +
                " | " + row[4] + " | v" + row[6];
            if (!evidence.equals(lastTwinEvidence[module])) {
                lastTwinEvidence[module] = evidence;
                addHistory(module, "TWIN " + display(row[4]) +
                    " | " + display(row[2]));
            }
        }
    }

    synchronized void reset(String resetId) {
        eventSequence = 0L;
        for (int module = 0; module < MODULE_COUNT; module++) {
            history[module].clear();
            lastTwinEvidence[module] = null;
            addHistory(module, "RESET " + safe(resetId) +
                " | awaiting new live evidence");
        }
    }

    synchronized Snapshot publish(
        ABSVisualisationFlowModel.FlowSnapshot flow,
        ABSLiveTwinModel.Snapshot twin,
        ABSVisualisationTeamIpModel.Snapshot team,
        int[] statuses,
        boolean[] received
    ) {
        version++;
        String[][] workpieces = twin == null ? new String[0][] :
            twin.workpieces();
        String[][] resources = twin == null ? new String[0][] :
            twin.resources();
        ModuleContext[] contexts = new ModuleContext[MODULE_COUNT];
        for (int module = 0; module < MODULE_COUNT; module++) {
            contexts[module] = contextFor(
                module,
                flow,
                workpieces,
                resources,
                new ArrayList<String>(history[module])
            );
        }

        String[] active = selectActiveWorkpiece(workpieces);
        String activeOrder = active == null ? "--" : orderFrom(active[0]);
        String bottleContext = active == null ?
            "-- | Awaiting live evidence" :
            active[0] + " | " + active[4] + " / " + active[5] + " mL";
        String currentStage = active == null ? symbolicStage(flow) :
            active[1] + " @ " + active[2] + " | LIVE";
        String systemState = systemState(team, statuses, received);
        return new Snapshot(
            version,
            activeOrder,
            bottleContext,
            currentStage,
            systemState,
            contexts,
            workpieces,
            active == null ? "--" : active[4],
            active == null ? "--" : active[5],
            flow
        );
    }

    static double bottleScale(String sizeCode) {
        return BottleVisualGeometry.forSizeCode(sizeCode).getVisualScale();
    }

    private ModuleContext contextFor(
        int module,
        ABSVisualisationFlowModel.FlowSnapshot flow,
        String[][] workpieces,
        String[][] resources,
        List<String> recentHistory
    ) {
        String[] resource = findResource(resources, module);
        String[] workpiece = null;
        if (flow.isTwinDriven()) {
            // Display IDs are no longer bottle sequence numbers. Latest main
            // assigns them independently; only the full Twin key is identity.
            String key = flow.getModule(module).getCurrentBottleKey();
            workpiece = findWorkpiece(workpieces, key);
            return new ModuleContext(workpiece == null ? "--" : workpiece[0],
                workpiece == null ? "--" : display(workpiece[1]),
                workpiece == null ? "--" : display(workpiece[2]),
                resource != null && key.equals(resource[2]) ? display(resource[4]) : "--",
                workpiece == null ? "--" : workpiece[4],
                workpiece == null ? "--" : workpiece[5] + " mL",
                workpiece != null, recentHistory);
        }
        if (resource != null && !isUnknown(resource[2])) {
            workpiece = findWorkpiece(workpieces, resource[2]);
        }
        if (workpiece == null) {
            workpiece = findStageWorkpiece(workpieces, module);
        }
        if (workpiece != null || resource != null) {
            // Preserve identity bytes; formatting underscores as spaces would
            // break full-ID matching against resource completion evidence.
            String bottle = workpiece == null ? safe(resource[2]) :
                workpiece[0];
            String stage = workpiece == null ? "--" : display(workpiece[1]);
            String resourceName = resource != null ? display(resource[0]) :
                display(workpiece[2]);
            String operation = resource == null ||
                (workpiece != null && !workpiece[0].equals(resource[2])) ? "--" :
                display(resource[4]);
            String size = workpiece == null ? "--" : display(workpiece[4]);
            String capacity = workpiece == null ? "--" :
                display(workpiece[5]) + " mL";
            return new ModuleContext(
                bottle,
                stage,
                resourceName,
                operation,
                size,
                capacity,
                true,
                recentHistory
            );
        }

        ABSVisualisationFlowModel.ModuleSnapshot symbolic =
            flow.getModule(module);
        return new ModuleContext(
            symbolic.getCurrentBottleId() > 0 ?
                "B" + symbolic.getCurrentBottleId() + " (symbolic)" : "--",
            symbolic.getPhase() + " | SYMBOLIC",
            "--",
            "--",
            "--",
            "--",
            false,
            recentHistory
        );
    }

    private static String[] selectActiveWorkpiece(String[][] workpieces) {
        String[] selected = null;
        long selectedVersion = Long.MIN_VALUE;
        for (String[] row : workpieces) {
            if ("COMPLETE".equals(row[1])) continue;
            long candidate = numeric(row[3]);
            if (selected == null || candidate >= selectedVersion) {
                selected = row;
                selectedVersion = candidate;
            }
        }
        if (selected == null && workpieces.length > 0) {
            selected = workpieces[workpieces.length - 1];
        }
        return selected;
    }

    private static String[] findWorkpiece(String[][] rows, String identity) {
        for (String[] row : rows) {
            if (row[0].equals(identity)) return row;
        }
        return null;
    }

    private static String[] findStageWorkpiece(String[][] rows, int module) {
        String[] selected = null;
        long selectedVersion = Long.MIN_VALUE;
        for (String[] row : rows) {
            if (!contains(MODULE_STAGES[module], row[1])) continue;
            if (!isUnknown(row[2]) && !matchesResource(
                normalise(row[2]), MODULE_TOKENS[module], module
            )) continue;
            long candidate = numeric(row[3]);
            if (selected == null || candidate >= selectedVersion) {
                selected = row;
                selectedVersion = candidate;
            }
        }
        return selected;
    }

    private static String[] findResource(String[][] rows, int module) {
        String token = MODULE_TOKENS[module];
        for (String[] row : rows) {
            String text = normalise(row[0] + " " + row[1]);
            if (matchesResource(text, token, module)) return row;
        }
        return null;
    }

    private static boolean matchesResource(String text, String token, int module) {
        if (module == 0 && (text.indexOf("unloader") >= 0 ||
            text.indexOf("lid") >= 0)) return false;
        if (module == 3) return text.indexOf("filler a") >= 0 ||
            text.indexOf("fillera") >= 0;
        if (module == 4) return text.indexOf("filler b") >= 0 ||
            text.indexOf("fillerb") >= 0;
        if (module == 5 && text.indexOf("lid") < 0) return false;
        return text.indexOf(token) >= 0;
    }

    private static String symbolicStage(
        ABSVisualisationFlowModel.FlowSnapshot flow
    ) {
        for (int module = MODULE_COUNT - 1; module >= 0; module--) {
            ABSVisualisationFlowModel.ModuleSnapshot value =
                flow.getModule(module);
            if (value.getCurrentBottleId() > 0) {
                return "B" + value.getCurrentBottleId() + " @ " +
                    value.getPhase() + " | SYMBOLIC";
            }
        }
        return "-- | Awaiting live evidence";
    }

    private static String systemState(
        ABSVisualisationTeamIpModel.Snapshot team,
        int[] statuses,
        boolean[] received
    ) {
        ABSVisualisationTeamIpModel.ExtensionSnapshot ft = team.getExtension(
            ABSVisualisationTeamIpModel.M3_FAULT_TOLERANCE
        );
        if (ft.isLiveEvidenceAvailable() &&
            !"NORMAL".equals(ft.getLiveHeadline())) {
            return ft.getLiveHeadline() + " | LIVE FT";
        }
        boolean busy = false;
        boolean any = false;
        for (int index = 0; index < statuses.length; index++) {
            if (!received[index]) continue;
            any = true;
            if (statuses[index] == 4) return "FAULT | LIVE Controller";
            if (statuses[index] == 2) busy = true;
        }
        if (busy) return "PRODUCING | LIVE Controller";
        if (any) return "READY / IDLE | LIVE Controller";
        return "-- | Awaiting live evidence";
    }

    private void addHistory(int module, String message) {
        Deque<String> events = history[module];
        String entry = "#" + (++eventSequence) + "  " + message;
        if (!events.isEmpty() && stripSequence(events.peekFirst()).equals(message)) {
            return;
        }
        events.addFirst(entry);
        while (events.size() > HISTORY_LIMIT) events.removeLast();
    }

    private static String stripSequence(String entry) {
        int divider = entry.indexOf("  ");
        return divider < 0 ? entry : entry.substring(divider + 2);
    }

    private static String orderFrom(String bottle) {
        int marker = bottle.lastIndexOf("-B");
        if (marker > 0) return bottle.substring(0, marker);
        return "--";
    }

    private static int bottleSequence(String bottle) {
        if (bottle == null) return -1;
        int marker = bottle.lastIndexOf("-B");
        String value;
        if (marker >= 0 && marker + 2 < bottle.length()) {
            value = bottle.substring(marker + 2);
        }
        else if (bottle.matches("B[0-9]+")) {
            value = bottle.substring(1);
        }
        else {
            return -1;
        }
        try { return Integer.parseInt(value); }
        catch (RuntimeException invalid) { return -1; }
    }

    private static String[][] copyRows(String[][] rows) {
        String[][] copy = new String[rows.length][];
        for (int index = 0; index < rows.length; index++) {
            copy[index] = rows[index].clone();
        }
        return copy;
    }

    private static boolean contains(String[] values, String value) {
        for (String item : values) if (item.equals(value)) return true;
        return false;
    }

    private static boolean isUnknown(String value) {
        return value == null || value.length() == 0 || "--".equals(value) ||
            "-".equals(value) || "NONE".equalsIgnoreCase(value) ||
            "NO_FAULT".equalsIgnoreCase(value);
    }

    private static String display(String value) {
        return isUnknown(value) ? "--" : value.replace('_', ' ');
    }

    private static String safe(String value) {
        return value == null || value.length() == 0 ? "--" : value;
    }

    private static String normalise(String value) {
        return value.toLowerCase().replace('_', ' ').replace('-', ' ');
    }

    private static long numeric(String value) {
        try { return Long.parseLong(value); }
        catch (RuntimeException invalid) { return Long.MIN_VALUE; }
    }

    private static void checkIndex(int index) {
        if (index < 0 || index >= MODULE_COUNT) {
            throw new IllegalArgumentException("module index " + index);
        }
    }
}
