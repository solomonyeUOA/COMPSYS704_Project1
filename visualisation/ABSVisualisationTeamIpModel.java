/**
 * Read-only presentation model for the team Individual Project extensions.
 *
 * M2 snapshots expose confirmed workpiece/resource state and M4 size profiles.
 * M3 evidence is accepted only
 * from the Coordinator's display-only VIZ_FT_EVIDENCE signal. This class has
 * no command, actuator or recovery output.
 */
final class ABSVisualisationTeamIpModel {
    static final int M2_DIGITAL_TWIN = 0;
    static final int M3_FAULT_TOLERANCE = 1;
    static final int M4_TWO_SIZE = 2;
    static final int EXTENSION_COUNT = 3;

    static final class ExtensionSnapshot {
        private final String member;
        private final String title;
        private final String owner;
        private final String mode;
        private final String summary;
        private final String[] architectureNodes;
        private final String[] capabilityLines;
        private final boolean liveEvidenceAvailable;
        private final String liveHeadline;
        private final String[] liveLines;
        private final String m1Representation;

        ExtensionSnapshot(
            String memberName,
            String extensionTitle,
            String ownerText,
            String extensionMode,
            String summaryText,
            String[] nodes,
            String[] capabilities,
            boolean hasLiveEvidence,
            String evidenceHeadline,
            String[] evidenceLines,
            String representation
        ) {
            member = memberName;
            title = extensionTitle;
            owner = ownerText;
            mode = extensionMode;
            summary = summaryText;
            architectureNodes = nodes.clone();
            capabilityLines = capabilities.clone();
            liveEvidenceAvailable = hasLiveEvidence;
            liveHeadline = evidenceHeadline;
            liveLines = evidenceLines.clone();
            m1Representation = representation;
        }

        String getMember() {
            return member;
        }

        String getTitle() {
            return title;
        }

        String getOwner() {
            return owner;
        }

        String getMode() {
            return mode;
        }

        String getSummary() {
            return summary;
        }

        String[] getArchitectureNodes() {
            return architectureNodes.clone();
        }

        String[] getCapabilityLines() {
            return capabilityLines.clone();
        }

        boolean isLiveEvidenceAvailable() {
            return liveEvidenceAvailable;
        }

        String getLiveHeadline() {
            return liveHeadline;
        }

        String[] getLiveLines() {
            return liveLines.clone();
        }

        String getM1Representation() {
            return m1Representation;
        }
    }

    static final class Snapshot {
        private final long version;
        private final ExtensionSnapshot[] extensions;

        Snapshot(long snapshotVersion, ExtensionSnapshot[] values) {
            version = snapshotVersion;
            extensions = values.clone();
        }

        long getVersion() {
            return version;
        }

        ExtensionSnapshot getExtension(int index) {
            checkIndex(index);
            return extensions[index];
        }
    }

    private long version;
    private boolean m3LiveEvidence;
    private String m3State = "NO FT EVIDENCE OBSERVED";
    private String m3Source = "--";
    private String m3Event = "--";
    private String m3SafeStop = "--";
    private String m3Recovery = "--";
    private Snapshot published;
    private ABSLiveTwinModel.Snapshot twin;

    ABSVisualisationTeamIpModel() {
        publish();
    }

    synchronized boolean acceptM3Evidence(String payload) {
        if (payload == null) {
            return false;
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 6 || !"V1".equals(fields[0])) {
            return false;
        }
        for (int index = 1; index < fields.length; index++) {
            if (fields[index].trim().length() == 0) {
                return false;
            }
        }

        String state = stateLabel(fields[1]);
        if (state == null) {
            return false;
        }
        m3State = state;
        m3Source = displayToken(fields[2]);
        m3Event = "none".equalsIgnoreCase(fields[3]) ?
            "No active event" : displayToken(fields[3]);
        m3SafeStop = displayToken(fields[4]);
        m3Recovery = displayToken(fields[5]);
        m3LiveEvidence = true;
        publish();
        return true;
    }

    synchronized Snapshot getSnapshot() {
        return published;
    }

    synchronized void acceptTwinEvidence(ABSLiveTwinModel.Snapshot evidence) {
        if (twin == evidence) return;
        twin = evidence;
        publish();
    }

    private String[] twinLines() {
        if (twin == null) return new String[] {
            "No current bottle or resource location is inferred.",
            "Waiting for a live DigitalTwinCD snapshot."
        };
        return new String[] {
            "Workpieces: " + twin.workpieceCount() + " | Resources: " + twin.resourceCount(),
            "Rejected updates: " + twin.rejected,
            "Reset generation: " + twin.generation + " | Snapshot: " + twin.sequence,
            "Open the live tables for bottle identity, stage, size and resource state."
        };
    }

    private String[] sizeLines() {
        if (twin == null) return new String[] {
            "No symbolic bottle is guessed to be S or L.",
            "Waiting for confirmed bottle contexts from DigitalTwinCD."
        };
        java.util.List<String> lines = new java.util.ArrayList<String>();
        for (String[] row : twin.workpieces()) {
            lines.add(row[0] + ": " + row[4] + " / " + row[5] + " mL / " + row[1]);
        }
        if (lines.isEmpty()) lines.add("No current workpiece contexts (empty/reset state).");
        return lines.toArray(new String[lines.size()]);
    }

    private void publish() {
        version++;
        ExtensionSnapshot[] values = new ExtensionSnapshot[EXTENSION_COUNT];
        values[M2_DIGITAL_TWIN] = new ExtensionSnapshot(
            "M2",
            "DIGITAL TWIN",
            "DigitalTwinCD :14002 / DigitalTwinViewerCD :14003",
            "READ-ONLY",
            "Immutable workpiece and resource state representation",
            new String[] {
                "CONFIRMED PRODUCTION EVENTS",
                "DigitalTwinCD :14002",
                "WorkpieceTwin",
                "ResourceTwin",
                "DigitalTwinViewerCD :14003"
            },
            new String[] {
                "Workpiece Twin: identity, profile and lifecycle snapshot",
                "Resource Twin: status, operation and fault history",
                "Immutable snapshots reject duplicate or invalid updates",
                "Dedicated read-only viewer available on DigitalTwinViewerCD"
            },
            twin != null,
            twin == null ? "AWAITING LIVE SNAPSHOT" :
                "LIVE: " + twin.workpieceCount() + " BOTTLES / " + twin.resourceCount() + " RESOURCES",
            twinLines(),
            twin == null ? "Capability representation only | Awaiting live Twin snapshot" :
                "Live DigitalTwinCD snapshot | Confirmed events only"
        );
        values[M3_FAULT_TOLERANCE] = new ExtensionSnapshot(
            "M3",
            "FAULT TOLERANCE",
            "FaultSupervisorCD :13003",
            "READ-ONLY OBSERVATION",
            "Fault detection, safe-stop coordination and recovery evidence",
            new String[] {
                "Controller / Plant Faults",
                "FaultSupervisorCD :13003",
                "M1 Coordinator",
                "M1 Visualisation"
            },
            new String[] {
                "Fault alert and correlated event evidence",
                "Safe-stop request with M1 coordination hold",
                "Recovery ready/failed state; no automatic M1 resume"
            },
            m3LiveEvidence,
            m3LiveEvidence ? m3State : "NO FT EVIDENCE OBSERVED",
            new String[] {
                "Source: " + m3Source,
                "Event: " + m3Event,
                "Safe stop: " + m3SafeStop,
                "Recovery: " + m3Recovery
            },
            "Live Coordinator-observed FT evidence | No control outputs"
        );
        values[M4_TWO_SIZE] = new ExtensionSnapshot(
            "M4",
            "TWO-SIZE EXTENSION",
            "BottleContextRegistry / Filler / Capper / SortPack",
            "SUPPORTED CAPABILITY",
            "Canonical size context drives geometry-aware processing",
            new String[] {
                "Recognition",
                "BottleContextRegistry",
                "SMALL S",
                "LARGE L",
                "Geometry-aware Filler A / B",
                "Geometry-aware Capper",
                "Sort / Pack"
            },
            new String[] {
                "SMALL: S / 200 mL / GEOM_S / PACK_S",
                "LARGE: L / 500 mL / GEOM_L / PACK_L",
                "Recognition -> context -> fill/cap geometry -> sort/pack"
            },
            twin != null,
            twin == null ? "AWAITING LIVE SIZE CONTEXT" : "LIVE SIZE CONTEXTS: " + twin.workpieceCount(),
            sizeLines(),
            twin == null ? "Capability/profile representation only | Awaiting live size telemetry" :
                "Live bottle size/capacity from confirmed Registry context via DigitalTwinCD"
        );
        published = new Snapshot(version, values);
    }

    private static String stateLabel(String token) {
        if ("NORMAL".equals(token)) {
            return "NORMAL";
        }
        if ("FAULT_ALERT".equals(token)) {
            return "FAULT ALERT";
        }
        if ("FAULT_HOLD".equals(token)) {
            return "FAULT HOLD";
        }
        if ("RECOVERY_READY_HOLD".equals(token)) {
            return "RECOVERY READY / HOLD RETAINED";
        }
        if ("RECOVERY_FAILED_HOLD".equals(token)) {
            return "RECOVERY FAILED / HOLD RETAINED";
        }
        return null;
    }

    private static String displayToken(String token) {
        return token.replace('_', ' ');
    }

    private static void checkIndex(int index) {
        if (index < 0 || index >= EXTENSION_COUNT) {
            throw new IllegalArgumentException("team IP index " + index);
        }
    }
}
