import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Handwritten Swing view for the Overall ABS Visualisation Plant.
 *
 * It receives data only through ABSVisualisationPlantCD. It has no Controller
 * connections and contains no machine or Plant control logic. This view is
 * intentionally symbolic: it shows Controller state, batch progress and
 * shared visual-only bottle records without claiming real bottle tracking.
 */
public final class ABSVisualisation {
    private static final int LOADER = 0;
    private static final int CONVEYOR = 1;
    private static final int ROTARY = 2;
    private static final int FILLER_A = 3;
    private static final int FILLER_B = 4;
    private static final int LID = 5;
    private static final int CAPPER = 6;
    private static final int UNLOADER = 7;

    private static final int READY_STATUS = 1;
    private static final int BUSY_STATUS = 2;
    private static final int DONE_STATUS = 3;
    private static final int FAULT_STATUS = 4;
    private static final int ANIMATION_DELAY_MILLIS = 30;
    private static final int DETAIL_ANIMATION_DELAY_MILLIS = 30;
    private static final double DEMO_LIQUID_A_PERCENT = 60.0;
    private static final double DEMO_LIQUID_B_PERCENT = 40.0;
    private static final Color LIQUID_A_COLOR = new Color(42, 132, 210);
    private static final Color LIQUID_B_COLOR = new Color(124, 86, 190);
    private static final Color ACTIVE_FLOW_COLOR = new Color(31, 132, 190);
    private static final Color PASSIVE_FLOW_COLOR = new Color(170, 184, 198);
    private static final Font MODULE_PHASE_FONT =
        new Font(Font.SANS_SERIF, Font.PLAIN, 8);
    private static final String[] ROTARY_POSITION_LABELS = {
        "P1 LOAD",
        "P2 FILL",
        "P3 LID",
        "P4 CAP",
        "P5 TRANSFER",
        "P6 LABEL"
    };
    private static final boolean TRACE_ENABLED =
        Boolean.getBoolean("abs.visualisation.trace");

    private static final String[] MACHINE_NAMES = {
        "Bottle Loader",
        "Conveyor",
        "Rotary Turntable",
        "Filler A",
        "Filler B",
        "Lid Loader",
        "Capper",
        "Bottle Unloader"
    };
    private static final int[] STATUSES = new int[MACHINE_NAMES.length];
    private static final boolean[] HAS_STATUS =
        new boolean[MACHINE_NAMES.length];
    private static final ABSVisualisationFlowModel VISUAL_MODEL =
        new ABSVisualisationFlowModel();
    private static final ABSVisualisationTeamIpModel TEAM_IP_MODEL =
        new ABSVisualisationTeamIpModel();
    private static volatile ABSVisualisationFlowModel.FlowSnapshot
        renderSnapshot = VISUAL_MODEL.getSnapshot();
    private static volatile ABSVisualisationTeamIpModel.Snapshot
        teamIpSnapshot = TEAM_IP_MODEL.getSnapshot();

    private static volatile ABSVisualisation instance;
    private static int requiredBottles = 0;
    private static int completedBottles = 0;
    private static boolean requiredBottlesReceived = false;
    private static boolean completedBottlesReceived = false;

    private final JFrame frame;
    private final ProductionLinePanel productionLinePanel;
    private final TeamIpExtensionsPanel teamIpExtensionsPanel;
    private final JLabel requiredLabel;
    private final JLabel completedLabel;
    private final JLabel progressLabel;
    private final JProgressBar progressBar;
    private final Timer animationTimer;
    private final JDialog[] detailDialogs;
    private final ModuleDetailPanel[] detailPanels;
    private final JDialog[] teamIpDialogs;
    private final TeamIpDetailPanel[] teamIpDetailPanels;

    private ABSVisualisation() {
        frame = new JFrame(
            "Automated Bottling System - Symbolic Visualisation"
        );
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout(12, 10));
        detailDialogs = new JDialog[MACHINE_NAMES.length];
        detailPanels = new ModuleDetailPanel[MACHINE_NAMES.length];
        teamIpDialogs = new JDialog[
            ABSVisualisationTeamIpModel.EXTENSION_COUNT
        ];
        teamIpDetailPanels = new TeamIpDetailPanel[
            ABSVisualisationTeamIpModel.EXTENSION_COUNT
        ];

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(14, 12, 2, 12));
        JLabel title = new JLabel(
            "AUTOMATED BOTTLING SYSTEM",
            SwingConstants.CENTER
        );
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        header.add(title);

        JLabel subtitle = new JLabel(
            "Real-state-anchored production flow and shared detail timeline",
            SwingConstants.CENTER
        );
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        subtitle.setForeground(new Color(75, 82, 92));
        subtitle.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        header.add(subtitle);
        frame.add(header, BorderLayout.NORTH);

        productionLinePanel = new ProductionLinePanel(
            new DetailWindowOpener() {
                @Override
                public void openDetail(int machineIndex) {
                    openDetailWindow(machineIndex);
                }
            }
        );
        JPanel schematicPanel = new JPanel(new BorderLayout());
        schematicPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Live symbolic plant schematic"),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        schematicPanel.add(productionLinePanel, BorderLayout.CENTER);

        teamIpExtensionsPanel = new TeamIpExtensionsPanel(
            new TeamIpWindowOpener() {
                @Override
                public void openTeamIpDetail(int extensionIndex) {
                    openTeamIpDetailWindow(extensionIndex);
                }
            }
        );
        JPanel hierarchyPanel = new JPanel(new BorderLayout(0, 8));
        hierarchyPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        hierarchyPanel.add(schematicPanel, BorderLayout.CENTER);
        hierarchyPanel.add(teamIpExtensionsPanel, BorderLayout.SOUTH);
        frame.add(hierarchyPanel, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(0, 6));
        footer.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        footer.add(createLegendPanel(), BorderLayout.NORTH);

        JPanel progressPanel = new JPanel(new BorderLayout(10, 7));
        progressPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Current production batch"),
            BorderFactory.createEmptyBorder(7, 16, 10, 16)
        ));
        progressLabel = new JLabel(
            "Waiting for batch data",
            SwingConstants.CENTER
        );
        progressLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        progressPanel.add(progressLabel, BorderLayout.NORTH);

        progressBar = new JProgressBar(0, 1);
        progressBar.setValue(0);
        progressBar.setStringPainted(true);
        progressBar.setString("Waiting for batch data");
        progressBar.setPreferredSize(new Dimension(540, 24));
        progressPanel.add(progressBar, BorderLayout.CENTER);

        JPanel countPanel = new JPanel(new FlowLayout(
            FlowLayout.CENTER,
            36,
            0
        ));
        requiredLabel = createCountLabel("Required bottles: --");
        completedLabel = createCountLabel("Completed bottles: --");
        countPanel.add(requiredLabel);
        countPanel.add(completedLabel);
        progressPanel.add(countPanel, BorderLayout.SOUTH);
        footer.add(progressPanel, BorderLayout.CENTER);
        frame.add(footer, BorderLayout.SOUTH);

        animationTimer = new Timer(
            ANIMATION_DELAY_MILLIS,
            new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    VISUAL_MODEL.tickElapsed(System.nanoTime());
                    renderSnapshot = VISUAL_MODEL.getSnapshot();
                    teamIpSnapshot = TEAM_IP_MODEL.getSnapshot();
                    productionLinePanel.repaint();
                    teamIpExtensionsPanel.syncState();
                    refreshDetailPanels();
                    refreshVisualProgressLabel();
                }
            }
        );
        animationTimer.setCoalesce(true);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                animationTimer.stop();
                closeAllDetailWindows();
                synchronized (ABSVisualisation.class) {
                    if (instance == ABSVisualisation.this) {
                        instance = null;
                    }
                }
            }
        });

        frame.setPreferredSize(new Dimension(1280, 900));
        frame.setMinimumSize(new Dimension(1080, 780));
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setResizable(true);
    }

    /** Starts Swing asynchronously; headless tests retain console evidence. */
    public static void start() {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println(
                "ABS Visualisation started in headless test mode"
            );
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (instance == null) {
                    instance = new ABSVisualisation();
                    instance.refreshAll();
                    instance.animationTimer.start();
                    instance.frame.setVisible(true);
                    System.out.println("ABS Visualisation window opened");
                }
            }
        });
    }

    private void openDetailWindow(final int machineIndex) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    openDetailWindow(machineIndex);
                }
            });
            return;
        }
        if (machineIndex < 0 || machineIndex >= MACHINE_NAMES.length) {
            return;
        }

        JDialog existing = detailDialogs[machineIndex];
        if (existing != null && existing.isDisplayable()) {
            existing.setVisible(true);
            existing.toFront();
            existing.requestFocus();
            return;
        }

        final ModuleDetailPanel detailPanel =
            new ModuleDetailPanel(machineIndex);
        final JDialog dialog = new JDialog(
            frame,
            MACHINE_NAMES[machineIndex] + " - Detailed Visualisation",
            false
        );
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setContentPane(detailPanel);
        dialog.setSize(new Dimension(780, 590));
        dialog.setMinimumSize(new Dimension(650, 500));
        dialog.setLocationRelativeTo(frame);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                detailPanel.stopAnimation();
                if (detailDialogs[machineIndex] == dialog) {
                    detailDialogs[machineIndex] = null;
                    detailPanels[machineIndex] = null;
                }
            }
        });

        detailDialogs[machineIndex] = dialog;
        detailPanels[machineIndex] = detailPanel;
        detailPanel.startAnimation();
        dialog.setVisible(true);
    }

    private void openTeamIpDetailWindow(final int extensionIndex) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    openTeamIpDetailWindow(extensionIndex);
                }
            });
            return;
        }
        if (extensionIndex < 0 || extensionIndex >=
            ABSVisualisationTeamIpModel.EXTENSION_COUNT) {
            return;
        }

        JDialog existing = teamIpDialogs[extensionIndex];
        if (existing != null && existing.isDisplayable()) {
            existing.setVisible(true);
            existing.toFront();
            existing.requestFocus();
            return;
        }

        final TeamIpDetailPanel detailPanel =
            new TeamIpDetailPanel(extensionIndex);
        ABSVisualisationTeamIpModel.ExtensionSnapshot extension =
            teamIpSnapshot.getExtension(extensionIndex);
        final JDialog dialog = new JDialog(
            frame,
            extension.getMember() + " " + extension.getTitle() +
                " - Team IP Detail",
            false
        );
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setContentPane(detailPanel);
        dialog.setSize(new Dimension(760, 560));
        dialog.setMinimumSize(new Dimension(660, 500));
        dialog.setLocationRelativeTo(frame);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                if (teamIpDialogs[extensionIndex] == dialog) {
                    teamIpDialogs[extensionIndex] = null;
                    teamIpDetailPanels[extensionIndex] = null;
                }
            }
        });
        teamIpDialogs[extensionIndex] = dialog;
        teamIpDetailPanels[extensionIndex] = detailPanel;
        detailPanel.syncState();
        dialog.setVisible(true);
    }

    private void closeAllDetailWindows() {
        for (int index = 0; index < detailDialogs.length; index++) {
            if (detailPanels[index] != null) {
                detailPanels[index].stopAnimation();
            }
            if (detailDialogs[index] != null &&
                detailDialogs[index].isDisplayable()) {
                detailDialogs[index].dispose();
            }
            detailPanels[index] = null;
            detailDialogs[index] = null;
        }
        for (int index = 0; index < teamIpDialogs.length; index++) {
            if (teamIpDialogs[index] != null &&
                teamIpDialogs[index].isDisplayable()) {
                teamIpDialogs[index].dispose();
            }
            teamIpDetailPanels[index] = null;
            teamIpDialogs[index] = null;
        }
    }

    private void refreshDetailPanels() {
        for (int index = 0; index < detailPanels.length; index++) {
            if (detailPanels[index] != null) {
                detailPanels[index].syncRealState();
            }
        }
        for (int index = 0; index < teamIpDetailPanels.length; index++) {
            if (teamIpDetailPanels[index] != null) {
                teamIpDetailPanels[index].syncState();
            }
        }
    }

    public static synchronized void updateStatus(String machine, int status) {
        int index = machineIndex(machine);
        if (index < 0) {
            return;
        }
        if (HAS_STATUS[index] && STATUSES[index] == status) {
            return;
        }

        STATUSES[index] = status;
        HAS_STATUS[index] = true;
        traceRealInput(signalName(index), status);
        VISUAL_MODEL.acceptStatus(index, status);
        System.out.println(
            "ABS Visualisation " + MACHINE_NAMES[index] + "=" +
            statusName(status) + " (" + status + ")"
        );
        refreshStatusOnSwing();
    }

    public static synchronized void updateRequiredBottles(int required) {
        if (requiredBottlesReceived && requiredBottles == required) {
            return;
        }
        boolean firstRequiredSignal = !requiredBottlesReceived;
        requiredBottles = required;
        requiredBottlesReceived = true;
        traceRealInput("VIZ_REQUIRED_BOTTLES", required);
        VISUAL_MODEL.acceptRequired(required);
        if (firstRequiredSignal && completedBottlesReceived) {
            VISUAL_MODEL.acceptCompleted(completedBottles);
        }
        printAndRefreshProgress();
    }

    public static synchronized void updateCompletedBottles(int completed) {
        if (completedBottlesReceived && completedBottles == completed) {
            return;
        }
        completedBottles = completed;
        completedBottlesReceived = true;
        traceRealInput("VIZ_COMPLETED_BOTTLES", completed);
        VISUAL_MODEL.acceptCompleted(completed);
        printAndRefreshProgress();
    }

    /** Accepts M1-only read-only evidence already validated by Coordinator. */
    public static synchronized void updateFtEvidence(String evidence) {
        if (!TEAM_IP_MODEL.acceptM3Evidence(evidence)) {
            return;
        }
        teamIpSnapshot = TEAM_IP_MODEL.getSnapshot();
        if (TRACE_ENABLED) {
            System.out.println(
                "ABS_VIZ_REAL timestamp=" + System.currentTimeMillis() +
                " signal=VIZ_FT_EVIDENCE value=" + evidence
            );
        }
        final ABSVisualisation ui = instance;
        if (ui != null) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    ui.teamIpExtensionsPanel.syncState();
                    ui.refreshDetailPanels();
                }
            });
        }
    }

    public static String statusName(int status) {
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

    private static JPanel createLegendPanel() {
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.CENTER, 9, 2));
        legend.add(new JLabel("Controller status:"));
        legend.add(createLegendChip("WAITING", -1));
        legend.add(createLegendChip("IDLE", 0));
        legend.add(createLegendChip("READY", 1));
        legend.add(createLegendChip("BUSY", 2));
        legend.add(createLegendChip("DONE", 3));
        legend.add(createLegendChip("FAULT", 4));
        JLabel note = new JLabel(
            "  REAL status anchors one shared IDEALISED model; BUSY holds before completion."
        );
        note.setForeground(new Color(75, 82, 92));
        legend.add(note);
        return legend;
    }

    private static JLabel createLegendChip(String text, int status) {
        JLabel chip = new JLabel("  " + text + "  ");
        chip.setOpaque(true);
        chip.setForeground(Color.WHITE);
        chip.setBackground(statusColor(status));
        chip.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        chip.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        return chip;
    }

    private static JLabel createCountLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        return label;
    }

    private static int machineIndex(String machine) {
        for (int index = 0; index < MACHINE_NAMES.length; index++) {
            if (MACHINE_NAMES[index].equals(machine)) {
                return index;
            }
        }
        return -1;
    }

    private static String signalName(int machineIndex) {
        switch (machineIndex) {
            case LOADER:
                return "VIZ_LOADER_STATUS";
            case CONVEYOR:
                return "VIZ_CONVEYOR_STATUS";
            case ROTARY:
                return "VIZ_ROTARY_STATUS";
            case FILLER_A:
                return "VIZ_FILLER_A_STATUS";
            case FILLER_B:
                return "VIZ_FILLER_B_STATUS";
            case LID:
                return "VIZ_LID_STATUS";
            case CAPPER:
                return "VIZ_CAPPER_STATUS";
            case UNLOADER:
                return "VIZ_UNLOADER_STATUS";
            default:
                return "VIZ_UNKNOWN_STATUS";
        }
    }

    private static void traceRealInput(String signal, int value) {
        if (TRACE_ENABLED) {
            System.out.println(
                "ABS_VIZ_REAL timestamp=" + System.currentTimeMillis() +
                " signal=" + signal + " value=" + value
            );
        }
    }

    private static void refreshStatusOnSwing() {
        final ABSVisualisation ui = instance;
        if (ui == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                renderSnapshot = VISUAL_MODEL.getSnapshot();
                ui.productionLinePanel.repaint();
                ui.refreshDetailPanels();
            }
        });
    }

    private static void printAndRefreshProgress() {
        final int required = requiredBottles;
        final int completed = completedBottles;
        final boolean requiredReceived = requiredBottlesReceived;
        final boolean completedReceived = completedBottlesReceived;
        System.out.println(
            "ABS Visualisation Progress=" + completed + "/" + required
        );
        final ABSVisualisation ui = instance;
        if (ui == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                renderSnapshot = VISUAL_MODEL.getSnapshot();
                ui.applyProgress(
                    required,
                    completed,
                    requiredReceived,
                    completedReceived
                );
                ui.refreshDetailPanels();
            }
        });
    }

    private void refreshAll() {
        synchronized (ABSVisualisation.class) {
            renderSnapshot = VISUAL_MODEL.getSnapshot();
            teamIpSnapshot = TEAM_IP_MODEL.getSnapshot();
            productionLinePanel.repaint();
            teamIpExtensionsPanel.syncState();
            applyProgress(
                requiredBottles,
                completedBottles,
                requiredBottlesReceived,
                completedBottlesReceived
            );
            refreshDetailPanels();
        }
    }

    private static ABSVisualisationFlowModel.ModuleSnapshot renderModule(
        int index
    ) {
        return renderSnapshot.getModule(index);
    }

    private void applyProgress(
        int required,
        int completed,
        boolean requiredReceived,
        boolean completedReceived
    ) {
        requiredLabel.setText(
            "Required bottles: " + (requiredReceived ? required : "--")
        );
        completedLabel.setText(
            "Completed bottles: " + (completedReceived ? completed : "--")
        );

        String summary;
        if (!requiredReceived && !completedReceived) {
            summary = "Waiting for batch data";
        }
        else if (!requiredReceived) {
            summary = completed +
                " bottles completed; waiting for required count";
        }
        else if (!completedReceived) {
            summary = "Required: " + required +
                " bottles; waiting for completed count";
        }
        else {
            summary = completed + " / " + required +
                " bottles completed";
        }

        int idealised = VISUAL_MODEL.getVisualCompleted();
        String mode = displayModeName(VISUAL_MODEL.getModeName());
        progressLabel.setText(
            summary + "  |  IDEALISED: " + idealised +
            (requiredReceived ? " / " + required : "") + " (" + mode + ")"
        );
        int maximum = requiredReceived ? Math.max(1, required) :
            Math.max(1, completed);
        int value = completedReceived ? completed : 0;
        value = Math.max(0, Math.min(value, maximum));
        progressBar.setMaximum(maximum);
        progressBar.setValue(value);
        progressBar.setString(summary);
    }

    private static String displayModeName(String mode) {
        if (!TRACE_ENABLED && "BOUNDED CATCH-UP".equals(mode)) {
            return "Synchronising with production";
        }
        return mode;
    }

    private void refreshVisualProgressLabel() {
        synchronized (ABSVisualisation.class) {
            applyProgress(
                requiredBottles,
                completedBottles,
                requiredBottlesReceived,
                completedBottlesReceived
            );
        }
    }

    private static Color statusColor(int status) {
        switch (status) {
            case 0:
                return new Color(96, 105, 115);
            case 1:
                return new Color(40, 105, 180);
            case 2:
                return new Color(224, 132, 18);
            case 3:
                return new Color(34, 145, 72);
            case 4:
                return new Color(190, 43, 43);
            default:
                return new Color(88, 92, 98);
        }
    }

    private static Color paleStatusColor(int status, boolean received) {
        Color source = received ? statusColor(status) : statusColor(-1);
        int red = (source.getRed() + 255 * 5) / 6;
        int green = (source.getGreen() + 255 * 5) / 6;
        int blue = (source.getBlue() + 255 * 5) / 6;
        return new Color(red, green, blue);
    }

    private interface DetailWindowOpener {
        void openDetail(int machineIndex);
    }

    private interface TeamIpWindowOpener {
        void openTeamIpDetail(int extensionIndex);
    }

    /** Second hierarchy level: team extensions around the GP flow. */
    static final class TeamIpExtensionsPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private final JButton[] cards = new JButton[
            ABSVisualisationTeamIpModel.EXTENSION_COUNT
        ];

        TeamIpExtensionsPanel(final TeamIpWindowOpener opener) {
            setLayout(new BorderLayout(0, 6));
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                    "Team IP Extensions - represented by M1"
                ),
                BorderFactory.createEmptyBorder(4, 8, 8, 8)
            ));
            JLabel relationship = new JLabel(
                "M2 Digital Twin  |  M3 Fault Tolerance  |  " +
                "M4 Two-Size Context   ->   " +
                "M1 read-only hierarchical visualisation",
                SwingConstants.CENTER
            );
            relationship.setFont(new Font(
                Font.SANS_SERIF,
                Font.PLAIN,
                11
            ));
            relationship.setForeground(new Color(65, 77, 91));
            add(relationship, BorderLayout.NORTH);

            JPanel cardRow = new JPanel(new GridLayout(1, 3, 10, 0));
            for (int index = 0; index < cards.length; index++) {
                final int extensionIndex = index;
                JButton card = new JButton();
                card.setCursor(Cursor.getPredefinedCursor(
                    Cursor.HAND_CURSOR
                ));
                card.setFocusPainted(false);
                card.setOpaque(true);
                card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(
                        teamIpAccent(index),
                        2,
                        true
                    ),
                    BorderFactory.createEmptyBorder(5, 8, 5, 8)
                ));
                card.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        opener.openTeamIpDetail(extensionIndex);
                    }
                });
                cards[index] = card;
                cardRow.add(card);
            }
            cardRow.setPreferredSize(new Dimension(0, 106));
            add(cardRow, BorderLayout.CENTER);
            syncState();
        }

        void syncState() {
            ABSVisualisationTeamIpModel.Snapshot snapshot = teamIpSnapshot;
            for (int index = 0; index < cards.length; index++) {
                ABSVisualisationTeamIpModel.ExtensionSnapshot extension =
                    snapshot.getExtension(index);
                cards[index].setText(cardText(extension));
                cards[index].setBackground(cardBackground(index, extension));
                cards[index].setToolTipText(
                    "Open " + extension.getMember() + " " +
                    extension.getTitle() + " integration detail"
                );
            }
        }

        private static String cardText(
            ABSVisualisationTeamIpModel.ExtensionSnapshot extension
        ) {
            return "<html><div style='text-align:center'>" +
                "<b>" + extension.getMember() + " " +
                extension.getTitle() + "</b><br>" +
                extension.getSummary() + "<br>" +
                "<font color='#3d5268'>" + extension.getMode() +
                "</font><br><b>" + extension.getLiveHeadline() +
                "</b></div></html>";
        }

        private static Color cardBackground(
            int index,
            ABSVisualisationTeamIpModel.ExtensionSnapshot extension
        ) {
            if (index == ABSVisualisationTeamIpModel.M3_FAULT_TOLERANCE &&
                extension.getLiveHeadline().indexOf("FAULT") >= 0) {
                return new Color(255, 232, 232);
            }
            if (index == ABSVisualisationTeamIpModel.M3_FAULT_TOLERANCE &&
                extension.getLiveHeadline().indexOf("RECOVERY") >= 0) {
                return new Color(255, 246, 218);
            }
            return new Color(245, 249, 252);
        }

        private static Color teamIpAccent(int index) {
            switch (index) {
                case ABSVisualisationTeamIpModel.M2_DIGITAL_TWIN:
                    return new Color(54, 116, 173);
                case ABSVisualisationTeamIpModel.M3_FAULT_TOLERANCE:
                    return new Color(185, 75, 56);
                default:
                    return new Color(99, 79, 164);
            }
        }
    }

    /** Read-only detail view; it shares the current team-IP snapshot. */
    static final class TeamIpDetailPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private final int extensionIndex;
        private final JLabel title = new JLabel();
        private final JLabel owner = new JLabel();
        private final JLabel capability = new JLabel();
        private final JLabel evidence = new JLabel();
        private final JLabel boundary = new JLabel();

        TeamIpDetailPanel(int index) {
            extensionIndex = index;
            setLayout(new BorderLayout(12, 12));
            setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

            JPanel heading = new JPanel();
            heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
            title.setAlignmentX(Component.CENTER_ALIGNMENT);
            title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
            owner.setAlignmentX(Component.CENTER_ALIGNMENT);
            owner.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            owner.setForeground(new Color(72, 83, 96));
            heading.add(title);
            heading.add(Box.createVerticalStrut(5));
            heading.add(owner);
            add(heading, BorderLayout.NORTH);

            JPanel sections = new JPanel(new GridLayout(1, 2, 12, 0));
            sections.add(sectionPanel("IMPLEMENTED CAPABILITY", capability));
            sections.add(sectionPanel("AVAILABLE LIVE EVIDENCE", evidence));
            add(sections, BorderLayout.CENTER);

            boundary.setHorizontalAlignment(SwingConstants.CENTER);
            boundary.setOpaque(true);
            boundary.setBackground(new Color(235, 241, 247));
            boundary.setForeground(new Color(48, 66, 84));
            boundary.setBorder(BorderFactory.createEmptyBorder(10, 8, 10, 8));
            add(boundary, BorderLayout.SOUTH);
            syncState();
        }

        void syncState() {
            ABSVisualisationTeamIpModel.ExtensionSnapshot extension =
                teamIpSnapshot.getExtension(extensionIndex);
            title.setText(extension.getMember() + " IP - " +
                extension.getTitle());
            owner.setText(extension.getOwner() + "  |  " +
                extension.getMode());
            capability.setText(linesHtml(
                extension.getSummary(),
                extension.getCapabilityLines()
            ));
            evidence.setText(linesHtml(
                extension.getLiveHeadline(),
                extension.getLiveLines()
            ));
            boundary.setText(
                "<html><b>M1 boundary:</b> representation and observation " +
                "only - no actuator, reset, safe-stop or resume command" +
                "</html>"
            );
        }

        private static JPanel sectionPanel(String heading, JLabel content) {
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(194, 205, 216)),
                BorderFactory.createEmptyBorder(14, 14, 14, 14)
            ));
            JLabel label = new JLabel(heading, SwingConstants.CENTER);
            label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            label.setForeground(new Color(46, 67, 88));
            panel.add(label, BorderLayout.NORTH);
            content.setVerticalAlignment(SwingConstants.TOP);
            content.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            panel.add(content, BorderLayout.CENTER);
            return panel;
        }

        private static String linesHtml(String headline, String[] lines) {
            StringBuilder text = new StringBuilder(
                "<html><div style='width:260px'><b>"
            );
            text.append(headline).append("</b><br><br>");
            for (String line : lines) {
                text.append("&#8226; ").append(line).append("<br><br>");
            }
            text.append("</div></html>");
            return text.toString();
        }
    }

    /** Custom symbolic plant renderer; it never infers bottle locations. */
    static final class ProductionLinePanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final int DESIGN_WIDTH = 1160;
        private static final int DESIGN_HEIGHT = 420;
        private static final Rectangle[] MODULE_HIT_REGIONS = {
            new Rectangle(20, 142, 116, 164),
            new Rectangle(153, 160, 104, 128),
            new Rectangle(275, 105, 158, 230),
            new Rectangle(452, 72, 128, 138),
            new Rectangle(452, 236, 128, 138),
            new Rectangle(607, 142, 112, 164),
            new Rectangle(740, 142, 112, 164),
            new Rectangle(992, 142, 146, 164)
        };
        private final DetailWindowOpener detailWindowOpener;
        private int hoveredModule = -1;

        ProductionLinePanel() {
            this(null);
        }

        ProductionLinePanel(DetailWindowOpener opener) {
            detailWindowOpener = opener;
            setOpaque(true);
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(DESIGN_WIDTH, DESIGN_HEIGHT));
            setMinimumSize(new Dimension(880, 360));
            setToolTipText(
                "Click a machine for a larger read-only detail view"
            );
            MouseAdapter mouseHandler = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent event) {
                    updateHover(event.getPoint());
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    setHoveredModule(-1);
                }

                @Override
                public void mouseClicked(MouseEvent event) {
                    if (!SwingUtilities.isLeftMouseButton(event)) {
                        return;
                    }
                    Point designPoint = toDesignPoint(event.getPoint());
                    int machineIndex = moduleAtDesignPoint(
                        designPoint.x,
                        designPoint.y
                    );
                    if (machineIndex >= 0 && detailWindowOpener != null) {
                        detailWindowOpener.openDetail(machineIndex);
                    }
                }
            };
            addMouseListener(mouseHandler);
            addMouseMotionListener(mouseHandler);
        }

        int moduleAtDesignPoint(int designX, int designY) {
            Point point = new Point(designX, designY);
            for (int index = 0;
                index < MODULE_HIT_REGIONS.length;
                index++) {
                if (MODULE_HIT_REGIONS[index].contains(point)) {
                    return index;
                }
            }
            return -1;
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            Point designPoint = toDesignPoint(event.getPoint());
            int index = moduleAtDesignPoint(designPoint.x, designPoint.y);
            if (index < 0) {
                return "Read-only real-state-anchored production overview";
            }
            ABSVisualisationFlowModel.ModuleSnapshot module =
                renderModule(index);
            String bottle = module.getCurrentBottleId() > 0 ?
                "Symbolic Bottle B" + module.getCurrentBottleId() :
                "No active symbolic bottle";
            return "<html><b>" + MACHINE_NAMES[index] + "</b><br>" +
                bottle + "<br>Visual: " + module.getPhase() +
                "<br>Click for shared detail view</html>";
        }

        private void updateHover(Point componentPoint) {
            Point designPoint = toDesignPoint(componentPoint);
            setHoveredModule(moduleAtDesignPoint(
                designPoint.x,
                designPoint.y
            ));
        }

        private void setHoveredModule(int machineIndex) {
            if (hoveredModule == machineIndex) {
                return;
            }
            hoveredModule = machineIndex;
            setCursor(machineIndex >= 0 ?
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) :
                Cursor.getDefaultCursor());
            repaint();
        }

        private Point toDesignPoint(Point componentPoint) {
            double scale = Math.min(
                getWidth() / (double)DESIGN_WIDTH,
                getHeight() / (double)DESIGN_HEIGHT
            );
            if (scale <= 0.0) {
                return new Point(-1, -1);
            }
            double offsetX = (getWidth() - DESIGN_WIDTH * scale) / 2.0;
            double offsetY = (getHeight() - DESIGN_HEIGHT * scale) / 2.0;
            return new Point(
                (int)Math.floor((componentPoint.x - offsetX) / scale),
                (int)Math.floor((componentPoint.y - offsetY) / scale)
            );
        }

        boolean hasBusyMachine() {
            synchronized (ABSVisualisation.class) {
                for (int index = 0; index < STATUSES.length; index++) {
                    if (HAS_STATUS[index] &&
                        STATUSES[index] == BUSY_STATUS) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D)graphics.create();
            g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            );
            g2.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            );

            double scale = Math.min(
                getWidth() / (double)DESIGN_WIDTH,
                getHeight() / (double)DESIGN_HEIGHT
            );
            double offsetX = (getWidth() - DESIGN_WIDTH * scale) / 2.0;
            double offsetY = (getHeight() - DESIGN_HEIGHT * scale) / 2.0;
            g2.translate(offsetX, offsetY);
            g2.scale(scale, scale);

            int[] statuses = new int[STATUSES.length];
            boolean[] received = new boolean[HAS_STATUS.length];
            synchronized (ABSVisualisation.class) {
                System.arraycopy(
                    STATUSES, 0, statuses, 0, STATUSES.length
                );
                System.arraycopy(
                    HAS_STATUS, 0, received, 0, HAS_STATUS.length
                );
            }

            paintScene(g2, statuses, received);
            g2.dispose();
        }

        private void paintScene(
            Graphics2D g2,
            int[] statuses,
            boolean[] received
        ) {
            g2.setColor(new Color(248, 250, 252));
            g2.fillRoundRect(4, 4, DESIGN_WIDTH - 8, DESIGN_HEIGHT - 8, 18, 18);
            g2.setColor(new Color(207, 215, 224));
            g2.drawRoundRect(4, 4, DESIGN_WIDTH - 8, DESIGN_HEIGHT - 8, 18, 18);

            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g2.setColor(new Color(62, 72, 84));
            drawCenteredText(
                g2,
                "REAL Coordinator state + shared IDEALISED bottle flow  |  no real bottle telemetry or feedback",
                DESIGN_WIDTH / 2,
                27
            );

            drawFlowRibbon(g2);
            drawFlowConnections(g2);
            drawLoader(g2, 20, 142, 116, 164, statuses, received);
            drawConveyor(
                g2, 153, 160, 104, 128, "Input Conveyor", statuses, received
            );
            drawRotary(g2, 275, 105, 158, 230, statuses, received);
            drawFiller(
                g2, 452, 72, 128, 138, FILLER_A, "Filler A",
                LIQUID_A_COLOR, statuses, received
            );
            drawFiller(
                g2, 452, 236, 128, 138, FILLER_B, "Filler B",
                LIQUID_B_COLOR, statuses, received
            );
            drawLidLoader(g2, 607, 142, 112, 164, statuses, received);
            drawCapper(g2, 740, 142, 112, 164, statuses, received);
            drawPassiveDownstreamBoundary(g2, 870, 160, 104, 128);
            drawUnloader(g2, 992, 142, 146, 164, statuses, received);

            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g2.setColor(new Color(78, 86, 98));
            drawCenteredText(
                g2,
                "Bottle IDs/positions are IDEALISED shared visual records only; the exit graphic is not a second Controller.",
                DESIGN_WIDTH / 2,
                400
            );
        }

        private void drawFlowRibbon(Graphics2D g2) {
            g2.setColor(new Color(233, 240, 247));
            g2.fillRoundRect(28, 40, DESIGN_WIDTH - 56, 25, 12, 12);
            g2.setColor(new Color(44, 78, 108));
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            drawCenteredText(
                g2,
                "INPUT  >  LOAD  >  TRANSPORT  >  ROTARY PROCESSING  >  " +
                "FILL A  >  FILL B  >  LID  >  CAP  >  UNLOAD  >  COMPLETION",
                DESIGN_WIDTH / 2,
                57
            );
        }

        private void drawFlowConnections(Graphics2D g2) {
            Stroke originalStroke = g2.getStroke();
            g2.setStroke(new BasicStroke(2.1f));
            g2.setColor(PASSIVE_FLOW_COLOR);

            drawArrow(g2, 136, 224, 153, 224);
            drawArrow(g2, 257, 224, 275, 224);

            g2.draw(new Line2D.Double(433, 218, 442, 218));
            g2.draw(new Line2D.Double(442, 141, 442, 305));
            drawArrow(g2, 442, 141, 452, 141);
            drawArrow(g2, 442, 305, 452, 305);

            g2.draw(new Line2D.Double(580, 141, 592, 141));
            g2.draw(new Line2D.Double(580, 305, 592, 305));
            g2.draw(new Line2D.Double(592, 141, 592, 224));
            g2.draw(new Line2D.Double(592, 305, 592, 224));
            drawArrow(g2, 592, 224, 607, 224);

            drawArrow(g2, 719, 224, 740, 224);
            drawArrow(g2, 852, 224, 870, 224);
            drawArrow(g2, 974, 224, 992, 224);

            drawActiveConnector(g2, LOADER, 136, 224, 153, 224);
            drawActiveConnector(g2, CONVEYOR, 257, 224, 275, 224);
            drawActiveConnector(g2, FILLER_A, 433, 218, 452, 141);
            drawActiveConnector(g2, FILLER_B, 433, 224, 452, 305);
            drawActiveConnector(g2, LID, 580, 141, 607, 224);
            drawActiveConnector(g2, CAPPER, 719, 224, 740, 224);
            drawActiveConnector(g2, UNLOADER, 852, 224, 992, 224);
            g2.setStroke(originalStroke);
        }

        private void drawActiveConnector(
            Graphics2D g2,
            int moduleIndex,
            double startX,
            double startY,
            double endX,
            double endY
        ) {
            ABSVisualisationFlowModel.ModuleSnapshot module =
                renderModule(moduleIndex);
            if (module.getCurrentBottleId() <= 0) {
                return;
            }
            double progress = ease(module.getProgress() / 100.0);
            double markerX = startX + (endX - startX) * progress;
            double markerY = startY + (endY - startY) * progress;
            Stroke original = g2.getStroke();
            g2.setStroke(new BasicStroke(
                4.2f,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND
            ));
            g2.setColor(new Color(
                ACTIVE_FLOW_COLOR.getRed(),
                ACTIVE_FLOW_COLOR.getGreen(),
                ACTIVE_FLOW_COLOR.getBlue(),
                145
            ));
            g2.draw(new Line2D.Double(
                startX,
                startY,
                markerX,
                markerY
            ));
            g2.setColor(ACTIVE_FLOW_COLOR);
            g2.fill(new Ellipse2D.Double(
                markerX - 3.5,
                markerY - 3.5,
                7.0,
                7.0
            ));
            g2.setStroke(original);
        }

        private void drawLoader(
            Graphics2D g2,
            int x,
            int y,
            int width,
            int height,
            int[] statuses,
            boolean[] received
        ) {
            drawMachineFrame(
                g2, x, y, width, height, LOADER, "Bottle Loader",
                statuses, received
            );
            boolean busy = isBusy(LOADER, statuses, received);
            boolean done = isDone(LOADER, statuses, received);
            ABSVisualisationFlowModel.ModuleSnapshot shared =
                renderModule(LOADER);
            double progress = shared.getProgress();
            int centreX = x + width / 2;

            Polygon hopper = new Polygon();
            hopper.addPoint(centreX - 31, y + 42);
            hopper.addPoint(centreX + 31, y + 42);
            hopper.addPoint(centreX + 17, y + 75);
            hopper.addPoint(centreX - 17, y + 75);
            g2.setColor(new Color(192, 204, 216));
            g2.fillPolygon(hopper);
            g2.setColor(new Color(72, 83, 96));
            g2.drawPolygon(hopper);

            g2.setColor(new Color(245, 248, 251));
            drawBottle(g2, centreX - 22, y + 49, 10, 20,
                null, 0, false, false);
            drawBottle(g2, centreX - 5, y + 47, 10, 22,
                null, 0, false, false);
            drawBottle(g2, centreX + 12, y + 50, 10, 19,
                null, 0, false, false);

            g2.setColor(new Color(72, 83, 96));
            g2.fillRect(centreX - 5, y + 75, 10, 19);
            g2.drawLine(centreX - 24, y + 101, centreX + 28, y + 101);
            g2.drawLine(centreX + 28, y + 101, centreX + 43, y + 116);
            g2.drawLine(centreX + 43, y + 116, centreX + 48, y + 116);

            double gateRatio = progress < 20.0 ? progress / 20.0 :
                (progress < 85.0 ? 1.0 :
                    Math.max(0.0, (100.0 - progress) / 15.0));
            int gateLift = (int)Math.round(gateRatio * 8.0);
            g2.setColor(busy ? statusColor(BUSY_STATUS) :
                new Color(118, 132, 146));
            g2.setStroke(new BasicStroke(3.0f));
            g2.drawLine(
                centreX - 22,
                y + 96 - gateLift,
                centreX + 20,
                y + 96 + gateLift / 2
            );
            g2.setStroke(new BasicStroke(1.0f));

            int bottleX = centreX - 7;
            int bottleY = y + 78;
            if (progress >= 20.0 && progress < 55.0) {
                double release = (progress - 20.0) / 35.0;
                bottleY += (int)Math.round(release * 21.0);
            }
            else if (progress >= 55.0) {
                double transfer = Math.min(1.0, (progress - 55.0) / 45.0);
                bottleX += (int)Math.round(38.0 * transfer);
                bottleY += 21;
            }
            drawBottle(g2, bottleX, bottleY, 14, 28,
                null, 0, false, false);
            drawBottleIdentity(
                g2,
                bottleX + 7,
                bottleY + 29,
                shared.getCurrentBottleId()
            );
            if (busy) {
                g2.setColor(statusColor(BUSY_STATUS));
                drawArrow(g2, centreX + 13, y + 111,
                    centreX + 39, y + 111);
            }
            if (done) {
                drawDoneTick(g2, x + width - 17, y + 42);
            }
        }

        private void drawConveyor(
            Graphics2D g2,
            int x,
            int y,
            int width,
            int height,
            String title,
            int[] statuses,
            boolean[] received
        ) {
            drawMachineFrame(
                g2, x, y, width, height, CONVEYOR, title,
                statuses, received
            );
            boolean busy = isBusy(CONVEYOR, statuses, received);
            boolean done = isDone(CONVEYOR, statuses, received);
            ABSVisualisationFlowModel.ModuleSnapshot shared =
                renderModule(CONVEYOR);
            int beltX = x + 12;
            int beltY = y + 66;
            int beltWidth = width - 24;

            g2.setColor(new Color(111, 126, 141));
            g2.drawLine(beltX - 1, beltY - 14,
                beltX + beltWidth + 1, beltY - 14);
            g2.drawLine(beltX - 1, beltY + 28,
                beltX + beltWidth + 1, beltY + 28);
            g2.setColor(new Color(83, 94, 108));
            g2.fillRoundRect(beltX, beltY, beltWidth, 20, 9, 9);
            g2.setColor(new Color(207, 216, 225));
            for (int rollerX = beltX + 8;
                rollerX < beltX + beltWidth - 2;
                rollerX += 17) {
                g2.fillOval(rollerX, beltY + 5, 9, 9);
            }

            int offset = (int)Math.round(
                shared.getProgress() / 100.0 * 18.0
            ) % 18;
            g2.setColor(busy ? statusColor(BUSY_STATUS) :
                new Color(129, 141, 154));
            Stroke original = g2.getStroke();
            g2.setStroke(new BasicStroke(2.0f));
            for (int chevronX = beltX - 12 + offset;
                chevronX < beltX + beltWidth - 4;
                chevronX += 18) {
                g2.draw(new Line2D.Double(
                    chevronX, beltY - 4, chevronX + 7, beltY + 2
                ));
                g2.draw(new Line2D.Double(
                    chevronX + 7, beltY + 2, chevronX, beltY + 8
                ));
            }
            g2.setStroke(original);

            int travelRange = Math.max(1, beltWidth - 28);
            int travel = (int)Math.round(
                shared.getConveyorBottlePosition() * travelRange
            );
            int bottleX = beltX + 4 + travel;
            int bottleY = beltY - 28;
            drawBottle(
                g2,
                bottleX,
                bottleY,
                15,
                28,
                null,
                0,
                false,
                false
            );
            drawBottleIdentity(
                g2,
                bottleX + 7,
                bottleY + 29,
                shared.getCurrentBottleId()
            );
            if (done) {
                drawDoneTick(g2, x + width - 15, y + 36);
            }
        }

        private void drawRotary(
            Graphics2D g2,
            int x,
            int y,
            int width,
            int height,
            int[] statuses,
            boolean[] received
        ) {
            drawMachineFrame(
                g2, x, y, width, height, ROTARY, "Rotary Table",
                statuses, received
            );
            Stroke originalStroke = g2.getStroke();
            boolean busy = isBusy(ROTARY, statuses, received);
            boolean done = isDone(ROTARY, statuses, received);
            ABSVisualisationFlowModel.ModuleSnapshot shared =
                renderModule(ROTARY);
            int centreX = x + width / 2;
            int centreY = y + 105;
            int radius = 52;

            g2.setColor(new Color(218, 225, 232));
            g2.fillOval(
                centreX - radius, centreY - radius,
                radius * 2, radius * 2
            );
            g2.setColor(new Color(69, 82, 96));
            g2.setStroke(new BasicStroke(2.0f));
            g2.drawOval(
                centreX - radius, centreY - radius,
                radius * 2, radius * 2
            );
            g2.fillOval(centreX - 10, centreY - 10, 20, 20);

            int stationCount = shared.getRotaryStationCount();
            for (int station = 0; station < stationCount; station++) {
                double phaseAngle = Math.toRadians(shared.getRotaryAngle());
                double angle = -Math.PI / 2.0 + phaseAngle + station *
                    (Math.PI * 2.0 / stationCount);
                int stationX = centreX + (int)(Math.cos(angle) * 38);
                int stationY = centreY + (int)(Math.sin(angle) * 38);
                g2.setColor(new Color(248, 250, 252));
                g2.fillOval(stationX - 7, stationY - 7, 14, 14);
                g2.setColor(new Color(77, 90, 104));
                g2.drawOval(stationX - 7, stationY - 7, 14, 14);
                if (shared.isRotaryStationOccupied(station)) {
                    drawBottle(
                        g2,
                        stationX - 5,
                        stationY - 12,
                        10,
                        20,
                        null,
                        0,
                        false,
                        false
                    );
                    drawBottleIdentity(
                        g2,
                        stationX,
                        stationY + 13,
                        shared.getRotaryStationBottleId(station)
                    );
                }
                int labelX = centreX + (int)(Math.cos(angle) * 48);
                int labelY = centreY + (int)(Math.sin(angle) * 48);
                g2.setColor(new Color(55, 70, 84));
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 7));
                drawCenteredText(
                    g2,
                    "P" + (station + 1),
                    labelX,
                    labelY + 3
                );
            }

            double indicatorAngle = Math.toRadians(
                shared.getRotaryAngle() - 90.0
            );
            int indicatorX = centreX + (int)(Math.cos(indicatorAngle) * 47);
            int indicatorY = centreY + (int)(Math.sin(indicatorAngle) * 47);
            g2.setColor(busy ? statusColor(BUSY_STATUS) :
                new Color(103, 116, 130));
            g2.setStroke(new BasicStroke(3.0f));
            g2.draw(new Line2D.Double(
                centreX, centreY, indicatorX, indicatorY
            ));
            g2.setStroke(new BasicStroke(2.0f));
            g2.draw(new Arc2D.Double(
                centreX - 63,
                centreY - 63,
                126,
                126,
                32,
                236,
                Arc2D.OPEN
            ));
            drawArrow(g2, centreX - 55, centreY + 26,
                centreX - 59, centreY + 15);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            g2.setColor(new Color(79, 88, 99));
            drawCenteredText(g2, "6-position symbolic process map",
                centreX, y + 174);
            drawCenteredText(
                g2,
                shared.getRotaryPhase(),
                centreX,
                y + 188
            );
            if (done) {
                drawDoneTick(g2, x + width - 18, y + 42);
            }
            g2.setStroke(originalStroke);
        }

        private void drawFiller(
            Graphics2D g2,
            int x,
            int y,
            int width,
            int height,
            int index,
            String title,
            Color liquidColor,
            int[] statuses,
            boolean[] received
        ) {
            drawMachineFrame(
                g2, x, y, width, height, index, title,
                statuses, received
            );
            boolean busy = isBusy(index, statuses, received);
            boolean done = isDone(index, statuses, received);
            ABSVisualisationFlowModel.ModuleSnapshot shared =
                renderModule(index);
            int tankX = x + 12;
            int tankY = y + 37;
            int tankWidth = 45;
            int tankHeight = 35;

            g2.setColor(new Color(228, 234, 240));
            g2.fillRoundRect(tankX, tankY, tankWidth, tankHeight, 8, 8);
            g2.setColor(liquidColor);
            g2.fillRect(tankX + 4, tankY + 20, tankWidth - 8, 10);
            g2.setColor(new Color(71, 84, 98));
            g2.drawRoundRect(tankX, tankY, tankWidth, tankHeight, 8, 8);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g2.setColor(new Color(54, 65, 78));
            drawCenteredText(
                g2, index == FILLER_A ? "A" : "B",
                tankX + tankWidth / 2, tankY + 17
            );

            int nozzleX = x + 86;
            g2.setColor(new Color(72, 83, 96));
            g2.fillRect(tankX + tankWidth, tankY + 14,
                nozzleX - tankX - tankWidth + 4, 5);
            g2.fillOval(x + 64, tankY + 10, 11, 11);
            g2.drawLine(x + 69, tankY + 6, x + 69, tankY + 25);
            g2.fillRect(nozzleX, tankY + 16, 6, 23);
            g2.fillRect(nozzleX - 4, tankY + 37, 14, 5);

            int componentLevel = (int)Math.round(index == FILLER_A ?
                shared.getLiquidALevel() : shared.getLiquidBLevel());
            int liquidALevel = index == FILLER_A ? componentLevel :
                (received[index] ?
                    (int)Math.round(DEMO_LIQUID_A_PERCENT) : 0);
            int liquidBLevel = index == FILLER_B ? componentLevel : 0;
            drawLayeredBottle(
                g2,
                nozzleX - 8,
                y + 73,
                22,
                35,
                LIQUID_A_COLOR,
                liquidALevel,
                LIQUID_B_COLOR,
                liquidBLevel,
                false,
                false
            );
            drawBottleIdentity(
                g2,
                nozzleX + 3,
                y + 109,
                shared.getCurrentBottleId()
            );

            if (busy) {
                int dropOffset = (int)Math.round(
                    shared.getProgress() * 0.51
                ) % 17;
                g2.setColor(liquidColor);
                g2.fill(new Ellipse2D.Double(
                    nozzleX - 1, tankY + 43 + dropOffset, 8, 10
                ));
            }
            else {
                g2.setColor(new Color(153, 164, 176));
                g2.drawOval(nozzleX, tankY + 44, 6, 8);
            }
            if (done) {
                drawDoneTick(g2, x + width - 15, y + 38);
            }
        }

        private void drawLidLoader(
            Graphics2D g2,
            int x,
            int y,
            int width,
            int height,
            int[] statuses,
            boolean[] received
        ) {
            drawMachineFrame(
                g2, x, y, width, height, LID, "Lid Loader",
                statuses, received
            );
            boolean busy = isBusy(LID, statuses, received);
            boolean done = isDone(LID, statuses, received);
            ABSVisualisationFlowModel.ModuleSnapshot shared =
                renderModule(LID);
            int motion = (int)Math.round(
                Math.min(1.0, shared.getProgress() / 100.0) * 34.0
            );

            g2.setColor(new Color(194, 204, 215));
            g2.fillRoundRect(x + 13, y + 40, 29, 64, 8, 8);
            g2.setColor(new Color(72, 84, 98));
            g2.drawRoundRect(x + 13, y + 40, 29, 64, 8, 8);
            for (int lidY = y + 50; lidY <= y + 89; lidY += 13) {
                g2.drawOval(x + 17, lidY, 21, 6);
            }

            g2.drawLine(x + 42, y + 69, x + 72, y + 69);
            g2.drawLine(x + 72, y + 69, x + 72, y + 83);
            int lidY = done ? y + 82 : y + 52 + motion;
            g2.setColor(busy ? statusColor(BUSY_STATUS) :
                new Color(118, 132, 146));
            g2.fillRoundRect(x + 61, lidY, 24, 7, 5, 5);
            drawLayeredBottle(
                g2,
                x + 63,
                y + 88,
                21,
                38,
                LIQUID_A_COLOR,
                (int)Math.round(DEMO_LIQUID_A_PERCENT),
                LIQUID_B_COLOR,
                (int)Math.round(DEMO_LIQUID_B_PERCENT),
                done,
                false
            );
            drawBottleIdentity(
                g2,
                x + 73,
                y + 127,
                shared.getCurrentBottleId()
            );
            if (busy) {
                g2.setColor(statusColor(BUSY_STATUS));
                drawArrow(g2, x + 92, y + 60, x + 92, y + 86);
            }
            if (done) {
                drawDoneTick(g2, x + width - 15, y + 40);
            }
        }

        private void drawCapper(
            Graphics2D g2,
            int x,
            int y,
            int width,
            int height,
            int[] statuses,
            boolean[] received
        ) {
            drawMachineFrame(
                g2, x, y, width, height, CAPPER, "Capper",
                statuses, received
            );
            Stroke originalStroke = g2.getStroke();
            boolean busy = isBusy(CAPPER, statuses, received);
            boolean done = isDone(CAPPER, statuses, received);
            ABSVisualisationFlowModel.ModuleSnapshot shared =
                renderModule(CAPPER);
            double capProgress = shared.getProgress();
            int motion = capProgress < 30.0 ?
                (int)Math.round(capProgress / 30.0 * 20.0) :
                (capProgress < 72.0 ? 20 :
                    (int)Math.round((100.0 - capProgress) / 28.0 * 20.0));
            int centreX = x + width / 2;
            int headY = y + 42 + motion;

            g2.setColor(new Color(76, 89, 103));
            g2.fillRect(centreX - 3, y + 35, 6, 31 + motion);
            g2.setColor(busy ? statusColor(BUSY_STATUS) :
                new Color(143, 156, 169));
            g2.fillRoundRect(centreX - 22, headY + 18, 44, 17, 7, 7);
            g2.setColor(new Color(72, 84, 98));
            g2.drawRoundRect(centreX - 22, headY + 18, 44, 17, 7, 7);
            g2.drawLine(centreX - 31, y + 128, centreX + 31, y + 128);

            drawLayeredBottle(
                g2,
                centreX - 11,
                y + 88,
                22,
                39,
                LIQUID_A_COLOR,
                (int)Math.round(DEMO_LIQUID_A_PERCENT),
                LIQUID_B_COLOR,
                (int)Math.round(DEMO_LIQUID_B_PERCENT),
                true,
                done
            );
            drawBottleIdentity(
                g2,
                centreX,
                y + 128,
                shared.getCurrentBottleId()
            );

            if (busy) {
                int startAngle = (int)Math.round(
                    shared.getTighteningAngle()
                ) % 360;
                g2.setColor(statusColor(BUSY_STATUS));
                g2.setStroke(new BasicStroke(2.0f));
                g2.draw(new Arc2D.Double(
                    centreX - 29, headY + 12, 58, 32,
                    startAngle, 115, Arc2D.OPEN
                ));
            }
            if (done) {
                g2.setColor(new Color(34, 145, 72));
                g2.setStroke(new BasicStroke(2.4f));
                g2.drawLine(centreX - 13, y + 84,
                    centreX + 13, y + 84);
                drawDoneTick(g2, x + width - 15, y + 40);
            }
            g2.setStroke(originalStroke);
        }

        private void drawUnloader(
            Graphics2D g2,
            int x,
            int y,
            int width,
            int height,
            int[] statuses,
            boolean[] received
        ) {
            drawMachineFrame(
                g2, x, y, width, height, UNLOADER, "Bottle Unloader",
                statuses, received
            );
            Stroke originalStroke = g2.getStroke();
            boolean busy = isBusy(UNLOADER, statuses, received);
            boolean done = isDone(UNLOADER, statuses, received);
            ABSVisualisationFlowModel.ModuleSnapshot shared =
                renderModule(UNLOADER);

            g2.setColor(new Color(80, 93, 107));
            g2.setStroke(new BasicStroke(3.0f));
            g2.drawLine(x + 16, y + 58, x + 66, y + 105);
            g2.drawLine(x + 16, y + 77, x + 52, y + 112);
            g2.drawLine(x + 52, y + 112, x + 126, y + 112);
            g2.setColor(new Color(222, 229, 236));
            g2.fillRoundRect(x + 92, y + 61, 39, 49, 7, 7);
            g2.setColor(new Color(91, 105, 120));
            g2.drawRoundRect(x + 92, y + 61, 39, 49, 7, 7);

            int offset = (int)Math.round(
                shared.getProgress() / 100.0 * 20.0
            ) % 20;
            g2.setColor(busy ? statusColor(BUSY_STATUS) :
                new Color(128, 141, 154));
            for (int arrowX = x + 53 + offset;
                arrowX < x + 112;
                arrowX += 20) {
                drawArrow(g2, arrowX, y + 93, arrowX + 12, y + 93);
            }
            int travel = (int)Math.round(
                Math.min(100.0, shared.getProgress()) / 100.0 * 67.0
            );
            int bottleX = x + 27 + travel;
            int bottleY = y + 73 + Math.min(19, travel / 3);
            drawLayeredBottle(
                g2,
                bottleX,
                bottleY,
                18,
                34,
                LIQUID_A_COLOR,
                (int)Math.round(DEMO_LIQUID_A_PERCENT),
                LIQUID_B_COLOR,
                (int)Math.round(DEMO_LIQUID_B_PERCENT),
                true,
                true
            );
            drawBottleIdentity(
                g2,
                bottleX + 9,
                bottleY + 35,
                shared.getCurrentBottleId()
            );
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            g2.setColor(new Color(79, 88, 99));
            drawCenteredText(
                g2, done ? "collected" : "collection output",
                x + width / 2, y + 128
            );
            if (done) {
                drawDoneTick(g2, x + width - 16, y + 41);
            }
            g2.setStroke(originalStroke);
        }

        private void drawMachineFrame(
            Graphics2D g2,
            int x,
            int y,
            int width,
            int height,
            int index,
            String title,
            int[] statuses,
            boolean[] received
        ) {
            ABSVisualisationFlowModel.ModuleSnapshot module =
                renderModule(index);
            boolean historicalDone = received[index] &&
                statuses[index] == DONE_STATUS &&
                module.getCurrentBottleId() <= 0;
            Color stateColor = historicalDone ?
                new Color(92, 112, 130) :
                (received[index] ? statusColor(statuses[index]) :
                    statusColor(-1));
            g2.setColor(historicalDone ?
                new Color(239, 243, 247) :
                paleStatusColor(statuses[index], received[index]));
            g2.fill(new RoundRectangle2D.Double(
                x, y, width, height, 14, 14
            ));
            Stroke original = g2.getStroke();
            g2.setStroke(new BasicStroke(
                isBusy(index, statuses, received) ? 2.8f : 1.5f
            ));
            g2.setColor(stateColor);
            g2.draw(new RoundRectangle2D.Double(
                x, y, width, height, 14, 14
            ));
            g2.setStroke(original);
            if (index == hoveredModule) {
                g2.setColor(new Color(31, 132, 190));
                g2.setStroke(new BasicStroke(2.2f));
                g2.draw(new RoundRectangle2D.Double(
                    x - 3,
                    y - 3,
                    width + 6,
                    height + 6,
                    17,
                    17
                ));
                g2.setStroke(original);
            }

            g2.setColor(new Color(42, 51, 62));
            int fontSize = width <= 105 ? 10 : 12;
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            drawCenteredText(g2, title, x + width / 2, y + 21);

            String visualPhase = module.getCurrentBottleId() > 0 ?
                "VISUAL B" + module.getCurrentBottleId() + ": " +
                    module.getPhase() :
                "VISUAL: " + module.getPhase();
            g2.setColor(new Color(76, 86, 98));
            g2.setFont(MODULE_PHASE_FONT);
            drawClippedCenteredText(
                g2,
                visualPhase,
                x + 6,
                y + height - 29,
                width - 12
            );

            drawStatusBadge(
                g2, x + 8, y + height - 23, width - 16, 16,
                index, statuses, received
            );
        }

        private void drawStatusBadge(
            Graphics2D g2,
            int x,
            int y,
            int width,
            int height,
            int index,
            int[] statuses,
            boolean[] received
        ) {
            ABSVisualisationFlowModel.ModuleSnapshot module =
                renderModule(index);
            boolean historicalDone = received[index] &&
                statuses[index] == DONE_STATUS &&
                module.getCurrentBottleId() <= 0;
            String text = historicalDone ? "LAST CYCLE COMPLETE" :
                "REAL " + (received[index] ?
                    statusName(statuses[index]) : "WAITING");
            Color color = historicalDone ? new Color(92, 112, 130) :
                (received[index] ? statusColor(statuses[index]) :
                    statusColor(-1));
            g2.setColor(color);
            g2.fillRoundRect(x, y, width, height, 8, 8);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            drawCenteredText(g2, text, x + width / 2, y + 12);
        }

        private static boolean isBusy(
            int index,
            int[] statuses,
            boolean[] received
        ) {
            return VISUAL_MODEL.isModuleMoving(index);
        }

        private static boolean isDone(
            int index,
            int[] statuses,
            boolean[] received
        ) {
            return received[index] && statuses[index] == DONE_STATUS &&
                renderModule(index).getCurrentBottleId() > 0;
        }

        private static void drawBottle(
            Graphics2D g2,
            int x,
            int y,
            int width,
            int height,
            Color liquidColor,
            int liquidPercent,
            boolean hasLid,
            boolean securedCap
        ) {
            int neckWidth = Math.max(5, width / 3);
            int neckX = x + (width - neckWidth) / 2;
            int shoulderY = y + 7;
            int bodyHeight = height - 7;

            g2.setColor(new Color(249, 252, 254));
            g2.fillRoundRect(x, shoulderY, width, bodyHeight, 7, 7);
            g2.fillRect(neckX, y + 2, neckWidth, 8);

            if (liquidColor != null && liquidPercent > 0) {
                int innerHeight = Math.max(1, bodyHeight - 5);
                int fillHeight = Math.max(
                    2,
                    innerHeight * Math.min(100, liquidPercent) / 100
                );
                int fillY = shoulderY + bodyHeight - 3 - fillHeight;
                g2.setColor(liquidColor);
                g2.fillRoundRect(
                    x + 3,
                    fillY,
                    Math.max(2, width - 6),
                    fillHeight,
                    4,
                    4
                );
            }

            g2.setColor(new Color(69, 87, 103));
            g2.drawRoundRect(x, shoulderY, width, bodyHeight, 7, 7);
            g2.drawRect(neckX, y + 2, neckWidth, 8);
            if (hasLid) {
                g2.setColor(securedCap ?
                    new Color(34, 145, 72) : new Color(78, 92, 108));
                g2.fillRoundRect(neckX - 2, y, neckWidth + 4, 5, 3, 3);
                if (securedCap) {
                    g2.setColor(new Color(19, 105, 52));
                    g2.drawLine(neckX - 1, y + 5,
                        neckX + neckWidth + 1, y + 5);
                }
            }
        }

        private void drawPassiveDownstreamBoundary(
            Graphics2D g2,
            int x,
            int y,
            int width,
            int height
        ) {
            g2.setColor(new Color(244, 247, 250));
            g2.fillRoundRect(x, y, width, height, 14, 14);
            Stroke original = g2.getStroke();
            g2.setStroke(new BasicStroke(
                1.5f,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
                1.0f,
                new float[] {5.0f, 5.0f},
                0.0f
            ));
            g2.setColor(new Color(148, 162, 176));
            g2.drawRoundRect(x, y, width, height, 14, 14);
            g2.setStroke(original);
            g2.setColor(new Color(67, 80, 94));
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            drawCenteredText(g2, "DOWNSTREAM", x + width / 2, y + 24);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            drawCenteredText(g2, "PROCESS /", x + width / 2, y + 47);
            drawCenteredText(g2, "COMPLETION", x + width / 2, y + 60);
            drawCenteredText(g2, "BOUNDARY", x + width / 2, y + 73);
            g2.setColor(PASSIVE_FLOW_COLOR);
            g2.setStroke(new BasicStroke(2.0f));
            for (int markerX = x + 15; markerX < x + width - 18;
                markerX += 22) {
                drawArrow(g2, markerX, y + 94, markerX + 13, y + 94);
            }
            g2.setStroke(original);
            g2.setColor(new Color(91, 105, 119));
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8));
            drawCenteredText(g2, "NO LIVE STATUS", x + width / 2,
                y + height - 13);
        }

        private static void drawBottleIdentity(
            Graphics2D g2,
            int centreX,
            int topY,
            int bottleId
        ) {
            if (bottleId <= 0) {
                return;
            }
            String text = "B" + bottleId;
            Font originalFont = g2.getFont();
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
            FontMetrics metrics = g2.getFontMetrics();
            int width = Math.max(18, metrics.stringWidth(text) + 6);
            g2.setColor(new Color(24, 104, 168));
            g2.fillRoundRect(centreX - width / 2, topY, width, 12, 7, 7);
            g2.setColor(Color.WHITE);
            drawCenteredText(g2, text, centreX, topY + 9);
            g2.setFont(originalFont);
        }

        private static void drawLayeredBottle(
            Graphics2D g2,
            int x,
            int y,
            int width,
            int height,
            Color bottomLiquidColor,
            int bottomLiquidPercent,
            Color topLiquidColor,
            int topLiquidPercent,
            boolean hasLid,
            boolean securedCap
        ) {
            int neckWidth = Math.max(5, width / 3);
            int neckX = x + (width - neckWidth) / 2;
            int shoulderY = y + 7;
            int bodyHeight = height - 7;

            g2.setColor(new Color(249, 252, 254));
            g2.fillRoundRect(x, shoulderY, width, bodyHeight, 7, 7);
            g2.fillRect(neckX, y + 2, neckWidth, 8);

            int boundedBottom = Math.max(
                0,
                Math.min(100, bottomLiquidPercent)
            );
            int boundedTop = Math.max(
                0,
                Math.min(100 - boundedBottom, topLiquidPercent)
            );
            int innerHeight = Math.max(1, bodyHeight - 5);
            int innerBottom = shoulderY + bodyHeight - 3;
            int bottomHeight = (int)Math.round(
                innerHeight * boundedBottom / 100.0
            );
            int topHeight = (int)Math.round(
                innerHeight * boundedTop / 100.0
            );
            int innerX = x + 3;
            int innerWidth = Math.max(2, width - 6);

            if (bottomHeight > 0 && bottomLiquidColor != null) {
                g2.setColor(bottomLiquidColor);
                g2.fillRect(
                    innerX,
                    innerBottom - bottomHeight,
                    innerWidth,
                    bottomHeight
                );
            }
            if (topHeight > 0 && topLiquidColor != null) {
                g2.setColor(topLiquidColor);
                g2.fillRect(
                    innerX,
                    innerBottom - bottomHeight - topHeight,
                    innerWidth,
                    topHeight
                );
            }

            g2.setColor(new Color(69, 87, 103));
            g2.drawRoundRect(x, shoulderY, width, bodyHeight, 7, 7);
            g2.drawRect(neckX, y + 2, neckWidth, 8);
            if (hasLid) {
                g2.setColor(securedCap ?
                    new Color(34, 145, 72) : new Color(78, 92, 108));
                g2.fillRoundRect(neckX - 2, y, neckWidth + 4, 5, 3, 3);
                if (securedCap) {
                    g2.setColor(new Color(19, 105, 52));
                    g2.drawLine(
                        neckX - 1,
                        y + 5,
                        neckX + neckWidth + 1,
                        y + 5
                    );
                }
            }
        }

        private static void drawDoneTick(
            Graphics2D g2,
            int centreX,
            int centreY
        ) {
            Stroke original = g2.getStroke();
            g2.setColor(new Color(34, 145, 72));
            g2.fillOval(centreX - 8, centreY - 8, 16, 16);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2.0f));
            g2.drawLine(centreX - 4, centreY,
                centreX - 1, centreY + 4);
            g2.drawLine(centreX - 1, centreY + 4,
                centreX + 5, centreY - 4);
            g2.setStroke(original);
        }

        private static void drawArrow(
            Graphics2D g2,
            double startX,
            double startY,
            double endX,
            double endY
        ) {
            g2.draw(new Line2D.Double(startX, startY, endX, endY));
            double angle = Math.atan2(endY - startY, endX - startX);
            double arrowLength = 6.5;
            double spread = Math.PI / 6.0;
            GeneralPath head = new GeneralPath();
            head.moveTo(endX, endY);
            head.lineTo(
                endX - arrowLength * Math.cos(angle - spread),
                endY - arrowLength * Math.sin(angle - spread)
            );
            head.lineTo(
                endX - arrowLength * Math.cos(angle + spread),
                endY - arrowLength * Math.sin(angle + spread)
            );
            head.closePath();
            g2.fill(head);
        }

        private static void drawCenteredText(
            Graphics2D g2,
            String text,
            int centreX,
            int baselineY
        ) {
            FontMetrics metrics = g2.getFontMetrics();
            int textX = centreX - metrics.stringWidth(text) / 2;
            g2.drawString(text, textX, baselineY);
        }

        private static double ease(double value) {
            double bounded = Math.max(0.0, Math.min(1.0, value));
            return bounded * bounded * (3.0 - 2.0 * bounded);
        }

        private static void drawClippedCenteredText(
            Graphics2D g2,
            String text,
            int x,
            int baselineY,
            int maximumWidth
        ) {
            FontMetrics metrics = g2.getFontMetrics();
            String visible = text;
            while (visible.length() > 4 &&
                metrics.stringWidth(visible + "...") > maximumWidth) {
                visible = visible.substring(0, visible.length() - 1);
            }
            if (!visible.equals(text)) {
                visible += "...";
            }
            int textX = x + Math.max(
                0,
                (maximumWidth - metrics.stringWidth(visible)) / 2
            );
            g2.drawString(visible, textX, baselineY);
        }
    }

    /** Read-only dialog content sharing the same state as the overview. */
    static final class ModuleDetailPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final int DETAIL_WIDTH = 500;
        private static final int DETAIL_HEIGHT = 430;

        private final int machineIndex;
        private ABSVisualisationFlowModel.ModuleSnapshot detailModel;
        private final DetailCanvas detailCanvas;
        private final JLabel realStatusValue;
        private final JLabel realBatchValue;
        private final JLabel phaseValue;
        private final JLabel primaryMetricValue;
        private final JLabel secondaryMetricValue;
        private final Timer detailTimer;

        private boolean lastStatusReceived;
        private int lastRealStatus = Integer.MIN_VALUE;

        ModuleDetailPanel(int index) {
            machineIndex = index;
            detailModel = renderModule(index);
            setLayout(new BorderLayout(10, 10));
            setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            JPanel titlePanel = new JPanel();
            titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));
            JLabel title = new JLabel(
                MACHINE_NAMES[index].toUpperCase(),
                SwingConstants.CENTER
            );
            title.setAlignmentX(Component.CENTER_ALIGNMENT);
            title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
            titlePanel.add(title);
            JLabel subtitle = new JLabel(
                "Read-only hierarchical module visualisation",
                SwingConstants.CENTER
            );
            subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
            subtitle.setForeground(new Color(76, 86, 98));
            subtitle.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
            titlePanel.add(subtitle);
            add(titlePanel, BorderLayout.NORTH);

            detailCanvas = new DetailCanvas();
            detailCanvas.setPreferredSize(new Dimension(
                DETAIL_WIDTH,
                DETAIL_HEIGHT
            ));
            detailCanvas.setBorder(BorderFactory.createLineBorder(
                new Color(190, 201, 212)
            ));
            add(detailCanvas, BorderLayout.CENTER);

            JPanel information = new JPanel();
            information.setLayout(new BoxLayout(
                information,
                BoxLayout.Y_AXIS
            ));
            information.setPreferredSize(new Dimension(235, 0));
            information.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Module information"),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
            ));

            JLabel realHeading = createInformationHeading(
                "REAL FROZEN-INTERFACE DATA",
                new Color(36, 92, 158)
            );
            information.add(realHeading);
            information.add(Box.createVerticalStrut(7));
            realStatusValue = createInformationValue("Status: WAITING");
            realStatusValue.setOpaque(true);
            realStatusValue.setForeground(Color.WHITE);
            realStatusValue.setBackground(statusColor(-1));
            realStatusValue.setBorder(BorderFactory.createEmptyBorder(
                6,
                7,
                6,
                7
            ));
            information.add(realStatusValue);
            information.add(Box.createVerticalStrut(6));
            JLabel realSource = createWrappedInformationLabel(
                "Source: Coordinator -> ABSVisualisationPlantCD -> " +
                "shared ABSVisualisation state"
            );
            information.add(realSource);
            information.add(Box.createVerticalStrut(6));
            realBatchValue = createWrappedInformationLabel("");
            realBatchValue.setVisible(index == UNLOADER);
            information.add(realBatchValue);

            information.add(Box.createVerticalStrut(18));
            JLabel idealisedHeading = createInformationHeading(
                "IDEALISED PROCESS MODEL",
                new Color(176, 93, 8)
            );
            information.add(idealisedHeading);
            information.add(Box.createVerticalStrut(7));
            phaseValue = createWrappedInformationLabel(
                "Phase: WAITING FOR REAL STATUS"
            );
            information.add(phaseValue);
            information.add(Box.createVerticalStrut(7));
            primaryMetricValue = createWrappedInformationLabel("");
            information.add(primaryMetricValue);
            information.add(Box.createVerticalStrut(5));
            secondaryMetricValue = createWrappedInformationLabel("");
            information.add(secondaryMetricValue);
            information.add(Box.createVerticalGlue());
            JLabel boundaryNote = createWrappedInformationLabel(
                "The mechanism motion and numeric process values are " +
                "generated locally for visualisation. They are not " +
                "Controller telemetry and never affect machine control."
            );
            boundaryNote.setForeground(new Color(98, 73, 39));
            information.add(boundaryNote);
            add(information, BorderLayout.EAST);

            detailTimer = new Timer(
                DETAIL_ANIMATION_DELAY_MILLIS,
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        syncRealState();
                        updateInformation();
                        detailCanvas.repaint();
                    }
                }
            );
            detailTimer.setCoalesce(true);
            syncRealState();
        }

        void startAnimation() {
            syncRealState();
            detailTimer.start();
        }

        void stopAnimation() {
            detailTimer.stop();
        }

        void syncRealState() {
            boolean received;
            int status;
            synchronized (ABSVisualisation.class) {
                received = HAS_STATUS[machineIndex];
                status = STATUSES[machineIndex];
            }
            detailModel = renderModule(machineIndex);
            int effectiveStatus = received ? status : -1;
            if (received != lastStatusReceived ||
                effectiveStatus != lastRealStatus) {
                lastStatusReceived = received;
                lastRealStatus = effectiveStatus;
            }
            updateInformation();
            detailCanvas.repaint();
        }

        int getDisplayedRealStatus() {
            return lastRealStatus;
        }

        ABSVisualisationFlowModel.ModuleSnapshot getDetailModel() {
            return detailModel;
        }

        private void updateInformation() {
            boolean historicalDone = lastStatusReceived &&
                lastRealStatus == DONE_STATUS &&
                detailModel.getCurrentBottleId() <= 0;
            String realStatusText = historicalDone ?
                "DONE (last cycle complete)" :
                (lastStatusReceived ? statusName(lastRealStatus) :
                    "WAITING");
            realStatusValue.setText("Raw state: " + realStatusText);
            realStatusValue.setBackground(historicalDone ?
                new Color(92, 112, 130) :
                (lastStatusReceived ? statusColor(lastRealStatus) :
                    statusColor(-1)));
            phaseValue.setText(
                wrapInformationText(
                    "Lifecycle: <b>" + detailModel.getLifecycle() +
                    "</b><br>Phase: <b>" + detailModel.getPhase() +
                    "</b><br>Current symbolic bottle: <b>" +
                    (detailModel.getCurrentBottleId() > 0 ?
                        "B" + detailModel.getCurrentBottleId() : "--") +
                    "</b><br>Reconciliation: <b>" +
                    displayModeName(VISUAL_MODEL.getModeName()) + "</b>"
                )
            );

            switch (machineIndex) {
                case LOADER:
                    primaryMetricValue.setText(
                        wrapInformationText(
                            "Cycle progress: " +
                            oneDecimal(detailModel.getProgress()) + "%"
                        )
                    );
                    secondaryMetricValue.setText(
                        wrapInformationText(
                            "Queue remaining: " +
                            renderSnapshot.getQueuedCount() +
                            "<br>Release position: " +
                            (detailModel.getCurrentBottleId() > 0 ?
                                "B" + detailModel.getCurrentBottleId() +
                                    " of " + renderSnapshot.getRequired() :
                                "waiting for real Loader cycle")
                        )
                    );
                    break;
                case CONVEYOR:
                    primaryMetricValue.setText(
                        wrapInformationText(
                            "Animation progress: " +
                            oneDecimal(detailModel.getProgress()) + "%"
                        )
                    );
                    secondaryMetricValue.setText(
                        wrapInformationText(
                            "Symbolic bottle position: " +
                            oneDecimal(
                                detailModel.getConveyorBottlePosition() *
                                100.0
                            ) + "%<br>Roller angle: " +
                            Math.round(detailModel.getRollerAngle()) +
                            " deg"
                        )
                    );
                    break;
                case ROTARY:
                    primaryMetricValue.setText(
                        wrapInformationText(
                            "Current index movement: " +
                            oneDecimal(detailModel.getRotaryAngle()) +
                            " deg (60 deg per real cycle)<br>" +
                            "Occupied symbolic positions: " +
                            detailModel.getRotaryOccupiedCount() + " / " +
                            detailModel.getRotaryStationCount()
                        )
                    );
                    secondaryMetricValue.setText(
                        wrapInformationText(
                            rotaryOccupancyText() +
                            "<br>Entered: " +
                            detailModel.getRotaryBottlesEntered() +
                            " | Exited: " +
                            detailModel.getRotaryBottlesExited()
                        )
                    );
                    break;
                case FILLER_A:
                    primaryMetricValue.setText(
                        wrapInformationText(
                            "Symbolic A phase display: " +
                            oneDecimal(detailModel.getLiquidALevel()) +
                            "%<br>Symbolic B phase display: 0.0%"
                        )
                    );
                    secondaryMetricValue.setText(
                        wrapInformationText(
                            "No recipe telemetry is available to IP." +
                            "<br>Total symbolic level: " +
                            oneDecimal(detailModel.getTotalFillLevel()) +
                            "%<br>Valve: " +
                            (detailModel.isRunning() ?
                                "OPEN" : "CLOSED") +
                            " (idealised)"
                        )
                    );
                    break;
                case FILLER_B:
                    primaryMetricValue.setText(
                        wrapInformationText(
                            "Symbolic A layer retained: " +
                            oneDecimal(detailModel.getLiquidALevel()) +
                            "%<br>Symbolic B layer added: " +
                            oneDecimal(detailModel.getLiquidBLevel()) + "%"
                        )
                    );
                    secondaryMetricValue.setText(
                        wrapInformationText(
                            "No recipe telemetry is available to IP." +
                            "<br>Total symbolic level: " +
                            oneDecimal(detailModel.getTotalFillLevel()) +
                            "%<br>Valve: " +
                            (detailModel.isRunning() ?
                                "OPEN" : "CLOSED") +
                            " (idealised)"
                        )
                    );
                    break;
                case LID:
                    primaryMetricValue.setText(
                        wrapInformationText(
                            "Placement progress: " +
                            oneDecimal(detailModel.getProgress()) + "%"
                        )
                    );
                    secondaryMetricValue.setText(
                        wrapInformationText(
                            "Lid position: symbolic feed-path model"
                        )
                    );
                    break;
                case CAPPER:
                    primaryMetricValue.setText(
                        wrapInformationText(
                            "Head-cycle progress: " +
                            oneDecimal(detailModel.getProgress()) + "%"
                        )
                    );
                    secondaryMetricValue.setText(
                        wrapInformationText(
                            "Tightening cue: " +
                            Math.round(detailModel.getTighteningAngle()) +
                            " deg"
                        )
                    );
                    break;
                case UNLOADER:
                    primaryMetricValue.setText(
                        wrapInformationText(
                            "Discharge progress: " +
                            oneDecimal(detailModel.getProgress()) + "%"
                        )
                    );
                    secondaryMetricValue.setText(
                        wrapInformationText(
                            detailModel.getPhase() +
                            "<br>Completion is counted only after real " +
                            "VIZ_COMPLETED_BOTTLES increases."
                        )
                    );
                    updateRealBatchInformation();
                    break;
                default:
                    primaryMetricValue.setText("No detail model");
                    secondaryMetricValue.setText("");
                    break;
            }
        }

        private void updateRealBatchInformation() {
            int required;
            int completed;
            boolean requiredReceived;
            boolean completedReceived;
            synchronized (ABSVisualisation.class) {
                required = requiredBottles;
                completed = completedBottles;
                requiredReceived = requiredBottlesReceived;
                completedReceived = completedBottlesReceived;
            }
            String requiredText = requiredReceived ?
                String.valueOf(required) : "--";
            String completedText = completedReceived ?
                String.valueOf(completed) : "--";
            realBatchValue.setText(
                wrapInformationText(
                    "REAL batch completed: <b>" + completedText +
                " / " + requiredText + "</b><br>Individual bottle " +
                    "identity is not available."
                )
            );
        }

        private String rotaryOccupancyText() {
            StringBuilder text = new StringBuilder("Symbolic positions: ");
            for (int station = 0;
                station < detailModel.getRotaryStationCount();
                station++) {
                if (station > 0) {
                    text.append("  ");
                }
                int bottleId =
                    detailModel.getRotaryStationBottleId(station);
                text.append('P').append(station + 1).append('=')
                    .append(bottleId > 0 ? "B" + bottleId : "empty");
            }
            return text.toString();
        }

        private static JLabel createInformationHeading(
            String text,
            Color color
        ) {
            JLabel label = new JLabel(text);
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            label.setForeground(color);
            return label;
        }

        private static JLabel createInformationValue(String text) {
            JLabel label = new JLabel(text);
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            label.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            return label;
        }

        private static JLabel createWrappedInformationLabel(String text) {
            JLabel label = new JLabel(wrapInformationText(text));
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            label.setMaximumSize(new Dimension(205, 120));
            return label;
        }

        private static String wrapInformationText(String text) {
            if (text.startsWith("<html>")) {
                return text;
            }
            return "<html><body width='185'>" + text +
                "</body></html>";
        }

        private static String oneDecimal(double value) {
            long scaled = Math.round(value * 10.0);
            long whole = scaled / 10L;
            long fraction = Math.abs(scaled % 10L);
            return whole + "." + fraction;
        }

        /** Large mechanism renderer driven only by one immutable snapshot. */
        private final class DetailCanvas extends JPanel {
            private static final long serialVersionUID = 1L;

            DetailCanvas() {
                setOpaque(true);
                setBackground(Color.WHITE);
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g2 = (Graphics2D)graphics.create();
                g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
                );
                g2.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                );
                double scale = Math.min(
                    getWidth() / (double)DETAIL_WIDTH,
                    getHeight() / (double)DETAIL_HEIGHT
                );
                double offsetX =
                    (getWidth() - DETAIL_WIDTH * scale) / 2.0;
                double offsetY =
                    (getHeight() - DETAIL_HEIGHT * scale) / 2.0;
                g2.translate(offsetX, offsetY);
                g2.scale(scale, scale);
                paintDetail(g2);
                g2.dispose();
            }

            private void paintDetail(Graphics2D g2) {
                g2.setColor(new Color(247, 249, 252));
                g2.fillRoundRect(
                    3,
                    3,
                    DETAIL_WIDTH - 6,
                    DETAIL_HEIGHT - 6,
                    16,
                    16
                );
                g2.setColor(new Color(229, 234, 240));
                for (int gridX = 25; gridX < DETAIL_WIDTH; gridX += 25) {
                    g2.drawLine(gridX, 36, gridX, DETAIL_HEIGHT - 34);
                }
                for (int gridY = 50;
                    gridY < DETAIL_HEIGHT - 34;
                    gridY += 25) {
                    g2.drawLine(8, gridY, DETAIL_WIDTH - 8, gridY);
                }

                g2.setColor(new Color(167, 91, 11));
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                ProductionLinePanel.drawCenteredText(
                    g2,
                    "IDEALISED PROCESS DETAIL - READ-ONLY OBSERVER",
                    DETAIL_WIDTH / 2,
                    25
                );

                switch (machineIndex) {
                    case LOADER:
                        drawLoaderDetail(g2);
                        break;
                    case CONVEYOR:
                        drawConveyorDetail(g2);
                        break;
                    case ROTARY:
                        drawRotaryDetail(g2);
                        break;
                    case FILLER_A:
                    case FILLER_B:
                        drawFillerDetail(g2);
                        break;
                    case LID:
                        drawLidDetail(g2);
                        break;
                    case CAPPER:
                        drawCapperDetail(g2);
                        break;
                    case UNLOADER:
                        drawUnloaderDetail(g2);
                        break;
                    default:
                        break;
                }

                g2.setColor(new Color(77, 88, 101));
                g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                ProductionLinePanel.drawCenteredText(
                    g2,
                    "Local animation values are not real Controller telemetry.",
                    DETAIL_WIDTH / 2,
                    DETAIL_HEIGHT - 12
                );
                drawStateOverlay(g2);
            }

            private void drawLoaderDetail(Graphics2D g2) {
                Polygon hopper = new Polygon();
                hopper.addPoint(85, 62);
                hopper.addPoint(300, 62);
                hopper.addPoint(255, 176);
                hopper.addPoint(135, 176);
                g2.setColor(new Color(202, 213, 224));
                g2.fillPolygon(hopper);
                g2.setColor(new Color(63, 79, 94));
                g2.setStroke(new BasicStroke(3.0f));
                g2.drawPolygon(hopper);
                for (int bottle = 0; bottle < 5; bottle++) {
                    ProductionLinePanel.drawBottle(
                        g2,
                        112 + bottle * 34,
                        89 + (bottle % 2) * 14,
                        25,
                        50,
                        null,
                        0,
                        false,
                        false
                    );
                }

                g2.setColor(new Color(69, 84, 99));
                g2.fillRect(184, 176, 22, 55);
                g2.drawLine(85, 303, 445, 303);
                g2.drawLine(85, 335, 445, 335);
                g2.drawLine(310, 303, 355, 270);
                g2.drawLine(310, 335, 355, 302);

                double progress = detailModel.getProgress();
                double gateOpen;
                if (progress < 20.0) {
                    gateOpen = progress / 20.0;
                }
                else if (progress < 85.0) {
                    gateOpen = 1.0;
                }
                else {
                    gateOpen = Math.max(0.0, (100.0 - progress) / 15.0);
                }
                g2.setColor(detailModel.isRunning() ?
                    statusColor(BUSY_STATUS) : new Color(105, 119, 133));
                g2.setStroke(new BasicStroke(7.0f));
                g2.drawLine(145, 232, 250,
                    232 + (int)Math.round(gateOpen * 34.0));

                double bottleX = 188.0;
                double bottleY = 188.0;
                if (progress >= 20.0 && progress < 55.0) {
                    double step = (progress - 20.0) / 35.0;
                    bottleY = 188.0 + step * 105.0;
                }
                else if (progress >= 55.0) {
                    double step = Math.min(1.0, (progress - 55.0) / 45.0);
                    bottleY = 286.0;
                    bottleX = 188.0 + step * 225.0;
                }
                ProductionLinePanel.drawBottle(
                    g2,
                    (int)Math.round(bottleX),
                    (int)Math.round(bottleY),
                    36,
                    72,
                    null,
                    0,
                    false,
                    false
                );
                g2.setColor(new Color(70, 87, 102));
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                g2.drawString("HOPPER / STORAGE", 118, 52);
                g2.drawString("RELEASE GATE", 105, 258);
                g2.drawString("OUTPUT POSITION", 350, 360);
            }

            private void drawConveyorDetail(Graphics2D g2) {
                int beltX = 42;
                int beltY = 213;
                int beltWidth = 416;
                g2.setColor(new Color(93, 108, 122));
                g2.setStroke(new BasicStroke(3.0f));
                g2.drawLine(beltX, beltY - 45,
                    beltX + beltWidth, beltY - 45);
                g2.drawLine(beltX, beltY + 82,
                    beltX + beltWidth, beltY + 82);
                g2.setColor(new Color(65, 78, 91));
                g2.fillRoundRect(beltX, beltY, beltWidth, 56, 18, 18);

                double angle = Math.toRadians(
                    detailModel.getRollerAngle()
                );
                for (int rollerX = 72;
                    rollerX <= 430;
                    rollerX += 52) {
                    g2.setColor(new Color(211, 219, 228));
                    g2.fillOval(rollerX - 17, beltY + 11, 34, 34);
                    g2.setColor(new Color(91, 105, 119));
                    g2.drawOval(rollerX - 17, beltY + 11, 34, 34);
                    int spokeX = rollerX +
                        (int)Math.round(Math.cos(angle) * 14.0);
                    int spokeY = beltY + 28 +
                        (int)Math.round(Math.sin(angle) * 14.0);
                    g2.drawLine(rollerX, beltY + 28, spokeX, spokeY);
                }

                int markerOffset = (int)Math.round(
                    detailModel.getProgress() / 100.0 * 36.0
                );
                g2.setColor(detailModel.isRunning() ?
                    statusColor(BUSY_STATUS) : new Color(135, 148, 160));
                for (int markerX = beltX - 30 + markerOffset;
                    markerX < beltX + beltWidth - 10;
                    markerX += 42) {
                    ProductionLinePanel.drawArrow(
                        g2,
                        markerX,
                        beltY - 13,
                        markerX + 23,
                        beltY - 13
                    );
                }

                int bottleX = 62 + (int)Math.round(
                    detailModel.getConveyorBottlePosition() * 345.0
                );
                ProductionLinePanel.drawBottle(
                    g2,
                    bottleX,
                    beltY - 71,
                    38,
                    76,
                    null,
                    0,
                    false,
                    false
                );
                g2.setColor(new Color(67, 83, 98));
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
                g2.drawString("SIDE RAIL", 45, 156);
                g2.drawString("MOVING BELT + ROLLERS", 158, 326);
                g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                g2.drawString(
                    "One REAL entry Conveyor; downstream boundary is passive.",
                    68,
                    365
                );
            }

            private void drawRotaryDetail(Graphics2D g2) {
                int centreX = 260;
                int centreY = 215;
                int tableRadius = 128;
                int stationRadius = 104;
                Point entryStation = rotaryPoint(
                    centreX,
                    centreY,
                    stationRadius,
                    0,
                    0.0
                );
                Point exitStation = rotaryPoint(
                    centreX,
                    centreY,
                    stationRadius,
                    detailModel.getRotaryStationCount() - 1,
                    0.0
                );

                g2.setColor(new Color(113, 128, 143));
                g2.setStroke(new BasicStroke(4.0f));
                g2.drawLine(18, entryStation.y - 18,
                    entryStation.x, entryStation.y - 18);
                g2.drawLine(18, entryStation.y + 18,
                    entryStation.x, entryStation.y + 18);
                g2.drawLine(18, exitStation.y - 18,
                    exitStation.x, exitStation.y - 18);
                g2.drawLine(18, exitStation.y + 18,
                    exitStation.x, exitStation.y + 18);
                g2.setColor(new Color(36, 92, 158));
                ProductionLinePanel.drawArrow(
                    g2,
                    24,
                    entryStation.y,
                    entryStation.x - 28,
                    entryStation.y
                );
                g2.setColor(new Color(34, 145, 72));
                ProductionLinePanel.drawArrow(
                    g2,
                    exitStation.x - 18,
                    exitStation.y,
                    24,
                    exitStation.y
                );

                g2.setColor(new Color(218, 226, 234));
                g2.fillOval(
                    centreX - tableRadius,
                    centreY - tableRadius,
                    tableRadius * 2,
                    tableRadius * 2
                );
                g2.setColor(new Color(64, 80, 95));
                g2.setStroke(new BasicStroke(4.0f));
                g2.drawOval(
                    centreX - tableRadius,
                    centreY - tableRadius,
                    tableRadius * 2,
                    tableRadius * 2
                );
                g2.setColor(new Color(64, 80, 95));
                g2.fillOval(centreX - 24, centreY - 24, 48, 48);

                double movementAngle = detailModel.getRotaryAngle();
                for (int station = 0;
                    station < detailModel.getRotaryStationCount();
                    station++) {
                    Point holder = rotaryPoint(
                        centreX,
                        centreY,
                        stationRadius,
                        station,
                        movementAngle
                    );
                    g2.setColor(new Color(112, 127, 141));
                    g2.setStroke(new BasicStroke(3.0f));
                    g2.drawLine(centreX, centreY, holder.x, holder.y);
                    g2.setColor(new Color(249, 251, 253));
                    g2.fillOval(holder.x - 25, holder.y - 25, 50, 50);
                    g2.setColor(new Color(65, 81, 96));
                    g2.drawOval(holder.x - 25, holder.y - 25, 50, 50);
                    boolean bottleIsExiting =
                        "EXITING".equals(detailModel.getRotaryPhase()) &&
                        station == detailModel.getRotaryStationCount() - 1;
                    if (detailModel.isRotaryStationOccupied(station) &&
                        !bottleIsExiting) {
                        ProductionLinePanel.drawBottle(
                            g2,
                            holder.x - 11,
                            holder.y - 23,
                            22,
                            46,
                            null,
                            0,
                            false,
                            false
                        );
                        ProductionLinePanel.drawBottleIdentity(
                            g2,
                            holder.x,
                            holder.y + 25,
                            detailModel.getRotaryStationBottleId(station)
                        );
                    }

                    Point label = rotaryPoint(
                        centreX,
                        centreY,
                        150,
                        station,
                        0.0
                    );
                    g2.setColor(Color.WHITE);
                    g2.fillRoundRect(label.x - 23, label.y - 11, 46, 22,
                        10, 10);
                    g2.setColor(new Color(45, 60, 75));
                    g2.drawRoundRect(label.x - 23, label.y - 11, 46, 22,
                        10, 10);
                    g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
                    ProductionLinePanel.drawCenteredText(
                        g2,
                        ROTARY_POSITION_LABELS[station],
                        label.x,
                        label.y + 3
                    );
                }

                if ("ENTRY".equals(detailModel.getRotaryPhase())) {
                    double entryProgress =
                        detailModel.getRotaryEntryProgress();
                    int bottleX = (int)Math.round(
                        24.0 + (entryStation.x - 24.0) * entryProgress
                    );
                    ProductionLinePanel.drawBottle(
                        g2,
                        bottleX - 11,
                        entryStation.y - 23,
                        22,
                        46,
                        null,
                        0,
                        false,
                        false
                    );
                    ProductionLinePanel.drawBottleIdentity(
                        g2,
                        bottleX,
                        entryStation.y + 24,
                        detailModel.getCurrentBottleId()
                    );
                }

                if ("EXITING".equals(detailModel.getRotaryPhase())) {
                    double exitProgress =
                        detailModel.getRotaryExitProgress();
                    int bottleX = (int)Math.round(
                        exitStation.x + (24.0 - exitStation.x) *
                            exitProgress
                    );
                    ProductionLinePanel.drawBottle(
                        g2,
                        bottleX - 11,
                        exitStation.y - 23,
                        22,
                        46,
                        null,
                        0,
                        false,
                        false
                    );
                    ProductionLinePanel.drawBottleIdentity(
                        g2,
                        bottleX,
                        exitStation.y + 24,
                        detailModel.getCurrentBottleId()
                    );
                }

                g2.setColor(detailModel.isRunning() ?
                    statusColor(BUSY_STATUS) : new Color(104, 119, 134));
                g2.setStroke(new BasicStroke(4.0f));
                g2.draw(new Arc2D.Double(112, 67, 296, 296,
                    35, 245, Arc2D.OPEN));
                ProductionLinePanel.drawArrow(
                    g2,
                    124,
                    302,
                    116,
                    281
                );
                g2.setColor(new Color(65, 81, 96));
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
                g2.drawString("ENTRY", 25, entryStation.y - 27);
                g2.drawString("EXIT", 25, exitStation.y - 27);
                ProductionLinePanel.drawCenteredText(
                    g2,
                    "SIX-POSITION M3 PROCESS MAP - SYMBOLIC OCCUPANCY ONLY",
                    centreX,
                    382
                );
            }

            private Point rotaryPoint(
                int centreX,
                int centreY,
                int radius,
                int station,
                double movementAngle
            ) {
                double angle = Math.toRadians(
                    180.0 - station *
                        (360.0 / detailModel.getRotaryStationCount()) -
                        movementAngle
                );
                return new Point(
                    centreX + (int)Math.round(Math.cos(angle) * radius),
                    centreY + (int)Math.round(Math.sin(angle) * radius)
                );
            }

            private void drawFillerDetail(Graphics2D g2) {
                Color liquidColor = machineIndex == FILLER_A ?
                    LIQUID_A_COLOR : LIQUID_B_COLOR;
                int tankX = 48;
                int tankY = 70;
                int tankWidth = 172;
                int tankHeight = 185;
                g2.setColor(new Color(227, 234, 241));
                g2.fillRoundRect(
                    tankX,
                    tankY,
                    tankWidth,
                    tankHeight,
                    20,
                    20
                );
                g2.setColor(liquidColor);
                g2.fillRoundRect(
                    tankX + 8,
                    tankY + 65,
                    tankWidth - 16,
                    tankHeight - 73,
                    12,
                    12
                );
                g2.setColor(new Color(63, 80, 96));
                g2.setStroke(new BasicStroke(4.0f));
                g2.drawRoundRect(
                    tankX,
                    tankY,
                    tankWidth,
                    tankHeight,
                    20,
                    20
                );
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
                ProductionLinePanel.drawCenteredText(
                    g2,
                    machineIndex == FILLER_A ? "A" : "B",
                    tankX + tankWidth / 2,
                    tankY + 48
                );

                int nozzleX = 351;
                g2.setColor(new Color(67, 82, 97));
                g2.fillRect(tankX + tankWidth, 126, 131, 18);
                g2.fillRect(nozzleX, 126, 17, 118);
                g2.fillRect(nozzleX - 15, 233, 47, 18);
                g2.setColor(detailModel.isRunning() ?
                    statusColor(BUSY_STATUS) : new Color(124, 137, 150));
                g2.fillOval(269, 111, 42, 42);
                g2.setColor(new Color(67, 82, 97));
                g2.drawLine(290, 103, 290, 160);

                ProductionLinePanel.drawLayeredBottle(
                    g2,
                    318,
                    270,
                    85,
                    130,
                    LIQUID_A_COLOR,
                    (int)Math.round(detailModel.getLiquidALevel()),
                    LIQUID_B_COLOR,
                    (int)Math.round(detailModel.getLiquidBLevel()),
                    false,
                    false
                );
                if (detailModel.isRunning()) {
                    g2.setColor(liquidColor);
                    g2.setStroke(new BasicStroke(7.0f));
                    g2.drawLine(nozzleX + 8, 252, nozzleX + 8, 287);
                }
                g2.setColor(new Color(66, 82, 98));
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
                g2.drawString("LIQUID TANK", 86, 280);
                g2.drawString("PIPE + VALVE", 240, 92);
                g2.drawString("NOZZLE", 376, 226);
                g2.drawString("TRANSPARENT SYMBOLIC BOTTLE", 260, 386);
                if (machineIndex == FILLER_B) {
                    g2.setColor(LIQUID_A_COLOR);
                    g2.drawString("A RETAINED", 275, 262);
                    g2.setColor(LIQUID_B_COLOR);
                    g2.drawString("+ B ADDED", 370, 262);
                }
            }

            private void drawLidDetail(Graphics2D g2) {
                g2.setColor(new Color(204, 214, 224));
                g2.fillRoundRect(45, 65, 120, 255, 16, 16);
                g2.setColor(new Color(64, 80, 96));
                g2.setStroke(new BasicStroke(3.0f));
                g2.drawRoundRect(45, 65, 120, 255, 16, 16);
                for (int lid = 0; lid < 8; lid++) {
                    int lidY = 90 + lid * 25;
                    g2.setColor(new Color(236, 240, 244));
                    g2.fillOval(68, lidY, 74, 15);
                    g2.setColor(new Color(76, 91, 106));
                    g2.drawOval(68, lidY, 74, 15);
                }
                g2.setColor(new Color(78, 94, 109));
                g2.drawLine(165, 116, 390, 116);
                g2.drawLine(165, 142, 365, 142);
                g2.drawLine(390, 116, 390, 274);
                g2.drawLine(365, 142, 365, 274);

                double progress = detailModel.getProgress();
                double lidX;
                double lidY;
                if (progress < 28.0) {
                    double step = progress / 28.0;
                    lidX = 119.0 + step * 55.0;
                    lidY = 92.0 + step * 35.0;
                }
                else if (progress < 66.0) {
                    double step = (progress - 28.0) / 38.0;
                    lidX = 174.0 + step * 204.0;
                    lidY = 127.0;
                }
                else {
                    double step = Math.min(1.0,
                        (progress - 66.0) / 34.0);
                    lidX = 378.0;
                    lidY = 127.0 + step * 147.0;
                }
                g2.setColor(detailModel.isRunning() ?
                    statusColor(BUSY_STATUS) : new Color(90, 105, 120));
                g2.fillRoundRect(
                    (int)Math.round(lidX - 30.0),
                    (int)Math.round(lidY),
                    60,
                    13,
                    7,
                    7
                );
                ProductionLinePanel.drawLayeredBottle(
                    g2,
                    343,
                    278,
                    70,
                    122,
                    LIQUID_A_COLOR,
                    (int)Math.round(DEMO_LIQUID_A_PERCENT),
                    LIQUID_B_COLOR,
                    (int)Math.round(DEMO_LIQUID_B_PERCENT),
                    progress >= 99.5,
                    false
                );
                g2.setColor(new Color(66, 82, 98));
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
                g2.drawString("LID MAGAZINE", 52, 52);
                g2.drawString("FEED TRACK", 238, 101);
                g2.drawString("PLACEMENT POINT", 326, 385);
            }

            private void drawCapperDetail(Graphics2D g2) {
                double progress = detailModel.getProgress();
                double headY;
                if (progress < 30.0) {
                    headY = 82.0 + progress / 30.0 * 145.0;
                }
                else if (progress < 72.0) {
                    headY = 227.0;
                }
                else {
                    headY = 227.0 -
                        (progress - 72.0) / 28.0 * 145.0;
                }

                g2.setColor(new Color(72, 87, 102));
                g2.fillRect(244, 48, 12, (int)Math.round(headY - 25.0));
                g2.setColor(new Color(207, 216, 226));
                g2.fillRoundRect(
                    165,
                    (int)Math.round(headY),
                    170,
                    53,
                    16,
                    16
                );
                g2.setColor(new Color(67, 82, 97));
                g2.setStroke(new BasicStroke(4.0f));
                g2.drawRoundRect(
                    165,
                    (int)Math.round(headY),
                    170,
                    53,
                    16,
                    16
                );
                ProductionLinePanel.drawLayeredBottle(
                    g2,
                    213,
                    268,
                    74,
                    132,
                    LIQUID_A_COLOR,
                    (int)Math.round(DEMO_LIQUID_A_PERCENT),
                    LIQUID_B_COLOR,
                    (int)Math.round(DEMO_LIQUID_B_PERCENT),
                    true,
                    progress >= 99.5
                );
                g2.setColor(new Color(67, 82, 97));
                g2.drawLine(135, 401, 365, 401);

                if (progress >= 30.0 && progress <= 72.0) {
                    double angle = detailModel.getTighteningAngle();
                    g2.setColor(statusColor(BUSY_STATUS));
                    g2.setStroke(new BasicStroke(4.0f));
                    g2.draw(new Arc2D.Double(
                        175,
                        headY - 15,
                        150,
                        83,
                        angle,
                        145,
                        Arc2D.OPEN
                    ));
                }
                g2.setColor(new Color(66, 82, 98));
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
                g2.drawString("VERTICAL GUIDE SHAFT", 268, 62);
                g2.drawString("CAPPING HEAD", 190,
                    (int)Math.round(headY - 9.0));
            }

            private void drawUnloaderDetail(Graphics2D g2) {
                g2.setColor(new Color(71, 87, 102));
                g2.setStroke(new BasicStroke(5.0f));
                g2.drawLine(42, 120, 285, 305);
                g2.drawLine(42, 168, 255, 332);
                g2.drawLine(255, 332, 450, 332);
                g2.drawLine(285, 305, 450, 305);
                g2.setColor(new Color(221, 228, 235));
                g2.fillRoundRect(342, 185, 118, 145, 16, 16);
                g2.setColor(new Color(77, 93, 108));
                g2.drawRoundRect(342, 185, 118, 145, 16, 16);

                double progress = detailModel.getProgress();
                double bottleX;
                double bottleY;
                if (progress < 70.0) {
                    double step = progress / 70.0;
                    bottleX = 68.0 + step * 220.0;
                    bottleY = 86.0 + step * 188.0;
                }
                else {
                    double step = Math.min(1.0,
                        (progress - 70.0) / 30.0);
                    bottleX = 288.0 + step * 95.0;
                    bottleY = 274.0 - step * 30.0;
                }
                ProductionLinePanel.drawLayeredBottle(
                    g2,
                    (int)Math.round(bottleX),
                    (int)Math.round(bottleY),
                    55,
                    100,
                    LIQUID_A_COLOR,
                    (int)Math.round(DEMO_LIQUID_A_PERCENT),
                    LIQUID_B_COLOR,
                    (int)Math.round(DEMO_LIQUID_B_PERCENT),
                    true,
                    true
                );

                g2.setColor(detailModel.isRunning() ?
                    statusColor(BUSY_STATUS) : new Color(112, 126, 140));
                for (int arrowX = 120; arrowX < 420; arrowX += 65) {
                    ProductionLinePanel.drawArrow(
                        g2,
                        arrowX,
                        350,
                        arrowX + 38,
                        350
                    );
                }
                g2.setColor(new Color(66, 82, 98));
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
                g2.drawString("DISCHARGE RAMP", 75, 215);
                g2.drawString("COLLECTION AREA", 340, 171);
            }

            private void drawStateOverlay(Graphics2D g2) {
                if (lastRealStatus == DONE_STATUS &&
                    detailModel.getCurrentBottleId() > 0) {
                    ProductionLinePanel.drawDoneTick(g2, 466, 48);
                }
                if (lastRealStatus == FAULT_STATUS) {
                    Stroke original = g2.getStroke();
                    g2.setColor(new Color(190, 43, 43));
                    g2.setStroke(new BasicStroke(5.0f));
                    g2.drawRoundRect(
                        10,
                        38,
                        DETAIL_WIDTH - 20,
                        DETAIL_HEIGHT - 76,
                        18,
                        18
                    );
                    g2.fillRoundRect(75, 42, 350, 30, 12, 12);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
                    ProductionLinePanel.drawCenteredText(
                        g2,
                        "FAULT - DETAIL ANIMATION STOPPED",
                        DETAIL_WIDTH / 2,
                        63
                    );
                    g2.setStroke(original);
                }
            }
        }
    }
}
