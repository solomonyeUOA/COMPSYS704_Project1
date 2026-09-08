/**
 * Read-only presentation model for the team Individual Project extensions.
 *
 * M2 and M4 expose capability information because their live snapshots are
 * not part of the M1 Visualisation interface. M3 evidence is accepted only
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
        private final String[] capabilityLines;
        private final boolean liveEvidenceAvailable;
        private final String liveHeadline;
        private final String[] liveLines;

        ExtensionSnapshot(
            String memberName,
            String extensionTitle,
            String ownerText,
            String extensionMode,
            String summaryText,
            String[] capabilities,
            boolean hasLiveEvidence,
            String evidenceHeadline,
            String[] evidenceLines
        ) {
            member = memberName;
            title = extensionTitle;
            owner = ownerText;
            mode = extensionMode;
            summary = summaryText;
            capabilityLines = capabilities.clone();
            liveEvidenceAvailable = hasLiveEvidence;
            liveHeadline = evidenceHeadline;
            liveLines = evidenceLines.clone();
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
    private String m3State = "NORMAL";
    private String m3Source = "M3 FaultSupervisor";
    private String m3Event = "No FT event received";
    private String m3SafeStop = "Not requested";
    private String m3Recovery = "Not active";
    private Snapshot published;

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
                "Workpiece Twin: identity, profile and lifecycle snapshot",
                "Resource Twin: status, operation and fault history",
                "Dedicated read-only viewer available on DigitalTwinViewerCD"
            },
            false,
            "LIVE SNAPSHOT NOT EXPOSED TO M1",
            new String[] {
                "No current bottle or resource location is inferred.",
                "This card represents the implemented M2 capability."
            }
        );
        values[M3_FAULT_TOLERANCE] = new ExtensionSnapshot(
            "M3",
            "FAULT TOLERANCE",
            "FaultSupervisorCD :13003",
            "READ-ONLY OBSERVATION",
            "Fault detection, safe-stop coordination and recovery evidence",
            new String[] {
                "Fault alert and correlated event evidence",
                "Safe-stop request with M1 coordination hold",
                "Recovery ready/failed state; no automatic M1 resume"
            },
            m3LiveEvidence,
            m3LiveEvidence ? m3State : "WAITING FOR M1-OBSERVABLE FT EVIDENCE",
            new String[] {
                "Source: " + m3Source,
                "Event: " + m3Event,
                "Safe stop: " + m3SafeStop,
                "Recovery: " + m3Recovery
            }
        );
        values[M4_TWO_SIZE] = new ExtensionSnapshot(
            "M4",
            "TWO-SIZE EXTENSION",
            "BottleContextRegistry / Filler / Capper / SortPack",
            "SUPPORTED CAPABILITY",
            "Canonical size context drives geometry-aware processing",
            new String[] {
                "SMALL: S / 200 mL / GEOM_S / PACK_S",
                "LARGE: L / 500 mL / GEOM_L / PACK_L",
                "Recognition -> context -> fill/cap geometry -> sort/pack"
            },
            false,
            "CURRENT LIVE SIZE NOT EXPOSED TO M1",
            new String[] {
                "No symbolic bottle is guessed to be S or L.",
                "RecognitionSimulator is environment stimulus, not the IP."
            }
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
