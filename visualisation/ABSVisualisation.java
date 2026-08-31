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
 * connections and contains no machine or Plant control logic. This Phase 1
 * view is intentionally symbolic: it shows Controller state, batch progress
 * and shared visual-only bottle records without claiming real bottle tracking.
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

    private static final int BUSY_STATUS = 2;
    private static final int ANIMATION_DELAY_MILLIS = 30;
    private static final int DETAIL_ANIMATION_DELAY_MILLIS = 30;
    private static final double DEMO_LIQUID_A_PERCENT = 60.0;
    private static final double DEMO_LIQUID_B_PERCENT = 40.0;
    private static final Color LIQUID_A_COLOR = new Color(42, 132, 210);
    private static final Color LIQUID_B_COLOR = new Color(124, 86, 190);

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
    private static final VisualisationSyncModel VISUAL_MODEL =
        new VisualisationSyncModel();

    private static volatile ABSVisualisation instance;
    private static int requiredBottles = 0;
    private static int completedBottles = 0;
    private static boolean requiredBottlesReceived = false;
    private static boolean completedBottlesReceived = false;

    private final JFrame frame;
    private final ProductionLinePanel productionLinePanel;
    private final JLabel requiredLabel;
    private final JLabel completedLabel;
    private final JLabel progressLabel;
    private final JProgressBar progressBar;
    private final Timer animationTimer;
    private final JDialog[] detailDialogs;
    private final ModuleDetailPanel[] detailPanels;

    private ABSVisualisation() {
        frame = new JFrame(
            "Automated Bottling System - Symbolic Visualisation"
        );
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout(12, 10));
        detailDialogs = new JDialog[MACHINE_NAMES.length];
        detailPanels = new ModuleDetailPanel[MACHINE_NAMES.length];

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
            "Phase 1 detailed symbolic process demonstration",
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
        frame.add(schematicPanel, BorderLayout.CENTER);

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
                    VISUAL_MODEL.tick();
                    productionLinePanel.advanceAnimation();
                    productionLinePanel.repaint();
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

        frame.setPreferredSize(new Dimension(1260, 760));
        frame.setMinimumSize(new Dimension(1080, 680));
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
    }

    private void refreshDetailPanels() {
        for (int index = 0; index < detailPanels.length; index++) {
            if (detailPanels[index] != null) {
                detailPanels[index].syncRealState();
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
        VISUAL_MODEL.acceptCompleted(completed);
        printAndRefreshProgress();
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
            "  REAL status anchors one shared IDEALISED batch model; DONE finalises motion."
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

    private static void refreshStatusOnSwing() {
        final ABSVisualisation ui = instance;
        if (ui == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
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
            productionLinePanel.repaint();
            applyProgress(
                requiredBottles,
                completedBottles,
                requiredBottlesReceived,
                completedBottlesReceived
            );
            refreshDetailPanels();
        }
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
        String mode = VISUAL_MODEL.getModeName();
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
        private static final Rectangle OUTPUT_CONVEYOR_HIT_REGION =
            new Rectangle(870, 160, 104, 128);

        private final DetailWindowOpener detailWindowOpener;
        private int animationPhase = 0;
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
            if (OUTPUT_CONVEYOR_HIT_REGION.contains(point)) {
                return CONVEYOR;
            }
            for (int index = 0;
                index < MODULE_HIT_REGIONS.length;
                index++) {
                if (MODULE_HIT_REGIONS[index].contains(point)) {
                    return index;
                }
            }
            return -1;
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

        void advanceAnimation() {
            animationPhase = (animationPhase + 1) % 120;
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
            drawConveyor(
                g2, 870, 160, 104, 128, "Output Conveyor", statuses, received
            );
            drawUnloader(g2, 992, 142, 146, 164, statuses, received);

            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g2.setColor(new Color(78, 86, 98));
            drawCenteredText(
                g2,
                "Bottle IDs/positions are IDEALISED shared visual records only; both belts share VIZ_CONVEYOR_STATUS.",
                DESIGN_WIDTH / 2,
                400
            );
        }

        private void drawFlowConnections(Graphics2D g2) {
            Stroke originalStroke = g2.getStroke();
            g2.setStroke(new BasicStroke(2.1f));
            g2.setColor(new Color(76, 93, 112));

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
            g2.setStroke(originalStroke);
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

            int gateLift = busy ? triangleWave(animationPhase, 8) : 0;
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

            int releaseStep = busy ? (animationPhase * 3) % 42 : 0;
            int bottleX = centreX - 7;
            int bottleY = y + 78;
            if (busy) {
                bottleX += Math.min(28, releaseStep);
                bottleY += Math.min(21, releaseStep * 3 / 4);
            }
            else if (done) {
                bottleX = centreX + 31;
                bottleY = y + 91;
            }
            drawBottle(g2, bottleX, bottleY, 14, 28,
                null, 0, false, false);
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
            boolean outputConveyor = title.startsWith("Output");
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

            int offset = busy ? (animationPhase * 4) % 18 : 0;
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
            int travel = busy ?
                (animationPhase * 4) % travelRange :
                (done ? travelRange : 0);
            int bottleX = beltX + 4 + travel;
            int bottleY = beltY - 28;
            Color bottleLiquid = outputConveyor ?
                new Color(92, 151, 204) : null;
            int bottleLevel = outputConveyor ? 72 : 0;
            drawBottle(
                g2,
                bottleX,
                bottleY,
                15,
                28,
                bottleLiquid,
                bottleLevel,
                outputConveyor,
                outputConveyor
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

            for (int station = 0; station < 5; station++) {
                double phaseAngle = busy ?
                    Math.toRadians((animationPhase * 8) % 360) : 0.0;
                double angle = -Math.PI / 2.0 + phaseAngle + station *
                    (Math.PI * 2.0 / 5.0);
                int stationX = centreX + (int)(Math.cos(angle) * 38);
                int stationY = centreY + (int)(Math.sin(angle) * 38);
                g2.setColor(new Color(248, 250, 252));
                g2.fillOval(stationX - 7, stationY - 7, 14, 14);
                g2.setColor(new Color(77, 90, 104));
                g2.drawOval(stationX - 7, stationY - 7, 14, 14);
                int visibleBottleCount = busy ?
                    Math.min(4, 1 + animationPhase / 18) :
                    (done ? 3 : 0);
                if (station < visibleBottleCount) {
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
                }
            }

            double indicatorAngle = busy ?
                Math.toRadians((animationPhase * 8) % 360 - 90) :
                -Math.PI / 2.0;
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
            drawCenteredText(g2, "5 symbolic stations", centreX, y + 174);
            drawCenteredText(
                g2,
                busy ? "indexing" : (done ? "settled" : "local view"),
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

            int componentTarget = (int)Math.round(
                index == FILLER_A ?
                    DEMO_LIQUID_A_PERCENT : DEMO_LIQUID_B_PERCENT
            );
            int componentLevel = done ? componentTarget :
                (busy ? Math.min(
                    componentTarget,
                    1 + (animationPhase * 2) % componentTarget
                ) : 0);
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

            if (busy) {
                int dropOffset = (animationPhase * 3) % 17;
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
            int motion = busy ? triangleWave(animationPhase, 34) : 0;

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
            int motion = busy ? triangleWave(animationPhase, 20) : 0;
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

            if (busy) {
                int startAngle = (animationPhase * 18) % 360;
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

            g2.setColor(new Color(80, 93, 107));
            g2.setStroke(new BasicStroke(3.0f));
            g2.drawLine(x + 16, y + 58, x + 66, y + 105);
            g2.drawLine(x + 16, y + 77, x + 52, y + 112);
            g2.drawLine(x + 52, y + 112, x + 126, y + 112);
            g2.setColor(new Color(222, 229, 236));
            g2.fillRoundRect(x + 92, y + 61, 39, 49, 7, 7);
            g2.setColor(new Color(91, 105, 120));
            g2.drawRoundRect(x + 92, y + 61, 39, 49, 7, 7);

            int offset = busy ? (animationPhase * 4) % 20 : 0;
            g2.setColor(busy ? statusColor(BUSY_STATUS) :
                new Color(128, 141, 154));
            for (int arrowX = x + 53 + offset;
                arrowX < x + 112;
                arrowX += 20) {
                drawArrow(g2, arrowX, y + 93, arrowX + 12, y + 93);
            }
            int travel = busy ? (animationPhase * 3) % 68 :
                (done ? 67 : 0);
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
            Color stateColor = received[index] ?
                statusColor(statuses[index]) : statusColor(-1);
            g2.setColor(paleStatusColor(statuses[index], received[index]));
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

            drawStatusBadge(
                g2, x + 8, y + height - 28, width - 16, 20,
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
            String text = received[index] ?
                statusName(statuses[index]) : "WAITING";
            Color color = received[index] ?
                statusColor(statuses[index]) : statusColor(-1);
            g2.setColor(color);
            g2.fillRoundRect(x, y, width, height, 8, 8);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            drawCenteredText(g2, text, x + width / 2, y + 14);
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
            return received[index] && statuses[index] == 3;
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

        private static int triangleWave(int phase, int amplitude) {
            int range = Math.max(2, amplitude * 2);
            int value = phase % range;
            return value <= amplitude ? value : range - value;
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
    }

    enum VisualLifecycle {
        IDLE,
        RUNNING,
        FINALISING,
        COMPLETE,
        FAULTED
    }

    /** A visualisation-only record; it is not a real bottle or Digital Twin. */
    static final class VirtualBottle {
        private final int displayId;
        private String idealisedStage;
        private double idealisedProgress;

        VirtualBottle(int id) {
            displayId = id;
            idealisedStage = "LOADER QUEUE";
            idealisedProgress = 0.0;
        }
    }

    /**
     * One shared, read-only reconciliation model for every overview/detail view.
     * Real Controller states and real batch counts only anchor this model. No
     * value produced here is sent to Coordinator or to a Controller.
     */
    static final class VisualisationSyncModel {
        private static final int ROTARY_STATIONS = 5;
        private static final int NO_BOTTLE = -1;
        private static final int MAX_CATCH_UP_STEPS = 6;

        private final DetailAnimationModel[] moduleViews =
            new DetailAnimationModel[MACHINE_NAMES.length];
        private final int[] moduleBottle =
            new int[MACHINE_NAMES.length];
        private final int[] rotaryBottle = new int[ROTARY_STATIONS];
        private final int[] realStatus = new int[MACHINE_NAMES.length];
        private final boolean[] hasRealStatus =
            new boolean[MACHINE_NAMES.length];

        private VirtualBottle[] bottles = new VirtualBottle[0];
        private int required;
        private int realCompleted;
        private int visualCompleted;
        private int nextBottle;
        private boolean hasRequired;
        private boolean hasCompleted;
        private boolean batchActive;
        private boolean catchUp;
        private String mode = "IDLE";

        private String rotaryPhase = "ENTRY";
        private int rotaryEntryBottle = NO_BOTTLE;
        private int rotaryExitBottle = NO_BOTTLE;
        private double rotaryEntryProgress;
        private double rotaryAngle;
        private double rotaryExitProgress;
        private int rotarySettlingTicks;
        private int rotaryEntered;
        private int rotaryExited;

        VisualisationSyncModel() {
            for (int index = 0; index < moduleViews.length; index++) {
                moduleViews[index] = new DetailAnimationModel(index);
                moduleBottle[index] = NO_BOTTLE;
                realStatus[index] = -1;
            }
            for (int station = 0; station < rotaryBottle.length; station++) {
                rotaryBottle[station] = NO_BOTTLE;
            }
            publishRotaryView("WAITING FOR BATCH");
        }

        synchronized void acceptRequired(int value) {
            int safeRequired = Math.max(0, value);
            if (!hasRequired || safeRequired != required) {
                resetBatch(safeRequired);
            }
            hasRequired = true;
        }

        synchronized void acceptCompleted(int value) {
            int safeCompleted = Math.max(0, value);
            boolean startsNewCycle = hasCompleted &&
                safeCompleted < realCompleted && hasRequired;
            if (startsNewCycle) {
                resetBatch(required);
                hasRequired = true;
            }
            hasCompleted = true;
            realCompleted = hasRequired ?
                Math.min(safeCompleted, required) : safeCompleted;
            if (hasRequired && required > 0) {
                batchActive = true;
            }
        }

        synchronized void acceptStatus(int index, int status) {
            if (index < 0 || index >= moduleViews.length) {
                return;
            }
            realStatus[index] = status;
            hasRealStatus[index] = true;
            moduleViews[index].setSharedRealStatus(status);
        }

        synchronized void tick() {
            if (!hasRequired || required <= 0) {
                mode = "IDLE";
                return;
            }
            if (hasFault()) {
                mode = "FAULTED";
                catchUp = false;
                return;
            }
            if (!batchActive && visualCompleted >= required) {
                mode = "COMPLETE";
                return;
            }

            int gap = Math.max(0, realCompleted - visualCompleted);
            catchUp = gap > 0;
            int steps = catchUp ?
                Math.min(MAX_CATCH_UP_STEPS, 2 + gap) : 1;
            mode = catchUp ? "CATCH_UP" : "RUNNING";
            for (int step = 0; step < steps; step++) {
                advanceOneStep();
            }

            boolean drained = visualCompleted >= required && !hasWork();
            if (drained) {
                batchActive = false;
                catchUp = false;
                mode = "COMPLETE";
                publishRotaryView("COMPLETE - STABLE EMPTY TABLE");
                for (int index = 0; index < moduleViews.length; index++) {
                    moduleViews[index].markSharedBatchComplete();
                }
            }
            else {
                updateLifecycleForCurrentWork();
            }
        }

        synchronized DetailAnimationModel getDetailModel(int index) {
            return moduleViews[index];
        }

        synchronized boolean isModuleMoving(int index) {
            return index >= 0 && index < moduleViews.length &&
                moduleViews[index].isRunning();
        }

        synchronized int getVisualCompleted() {
            return visualCompleted;
        }

        synchronized int getRealCompleted() {
            return realCompleted;
        }

        synchronized int getRequired() {
            return required;
        }

        synchronized String getModeName() {
            return mode;
        }

        synchronized int getVirtualBottleCount() {
            return bottles.length;
        }

        synchronized int getRotaryOccupiedCount() {
            int count = 0;
            for (int station = 0; station < rotaryBottle.length; station++) {
                if (rotaryBottle[station] != NO_BOTTLE) {
                    count++;
                }
            }
            return count;
        }

        synchronized String getBottleStage(int displayId) {
            int index = displayId - 1;
            return index >= 0 && index < bottles.length ?
                bottles[index].idealisedStage : "UNKNOWN";
        }

        private void resetBatch(int newRequired) {
            required = newRequired;
            realCompleted = 0;
            visualCompleted = 0;
            nextBottle = 0;
            hasCompleted = false;
            batchActive = newRequired > 0;
            catchUp = false;
            mode = newRequired > 0 ? "RUNNING" : "IDLE";
            bottles = new VirtualBottle[newRequired];
            for (int bottle = 0; bottle < bottles.length; bottle++) {
                bottles[bottle] = new VirtualBottle(bottle + 1);
            }
            for (int index = 0; index < moduleBottle.length; index++) {
                moduleBottle[index] = NO_BOTTLE;
                moduleViews[index].resetSharedBatch();
                if (hasRealStatus[index]) {
                    moduleViews[index].setSharedRealStatus(realStatus[index]);
                }
            }
            for (int station = 0; station < rotaryBottle.length; station++) {
                rotaryBottle[station] = NO_BOTTLE;
            }
            rotaryPhase = "ENTRY";
            rotaryEntryBottle = NO_BOTTLE;
            rotaryExitBottle = NO_BOTTLE;
            rotaryEntryProgress = 0.0;
            rotaryAngle = 0.0;
            rotaryExitProgress = 0.0;
            rotarySettlingTicks = 0;
            rotaryEntered = 0;
            rotaryExited = 0;
            publishRotaryView(newRequired > 0 ?
                "ENTRY - WAITING FOR BOTTLE #1" : "WAITING FOR BATCH");
        }

        private boolean hasFault() {
            for (int index = 0; index < realStatus.length; index++) {
                if (hasRealStatus[index] && realStatus[index] == 4) {
                    return true;
                }
            }
            return false;
        }

        private void advanceOneStep() {
            advanceUnloader();
            advanceLinearStage(CAPPER, UNLOADER);
            advanceLinearStage(LID, CAPPER);
            advanceLinearStage(FILLER_B, LID);
            advanceLinearStage(FILLER_A, FILLER_B);
            advanceRotary();
            advanceConveyor();
            advanceLoader();
            admitNextBottle();
        }

        private void admitNextBottle() {
            if (moduleBottle[LOADER] != NO_BOTTLE ||
                nextBottle >= bottles.length) {
                return;
            }
            int bottle = nextBottle++;
            moduleBottle[LOADER] = bottle;
            bottles[bottle].idealisedStage = "LOADER";
            bottles[bottle].idealisedProgress = 0.0;
            moduleViews[LOADER].beginSharedBottle(bottle + 1);
        }

        private void advanceLoader() {
            advanceLinearStage(LOADER, CONVEYOR);
        }

        private void advanceConveyor() {
            int bottle = moduleBottle[CONVEYOR];
            if (bottle == NO_BOTTLE) {
                return;
            }
            DetailAnimationModel view = moduleViews[CONVEYOR];
            if (view.getProgress() < 100.0) {
                view.advanceSharedBottle();
                bottles[bottle].idealisedProgress = view.getProgress();
            }
            if (view.getProgress() >= 100.0) {
                bottles[bottle].idealisedStage = "ROTARY ENTRY QUEUE";
                view.setSharedWaitingPhase(
                    "TRANSFER COMPLETE - WAITING FOR ROTARY ENTRY"
                );
            }
        }

        private void advanceLinearStage(int stage, int nextStage) {
            int bottle = moduleBottle[stage];
            if (bottle == NO_BOTTLE) {
                return;
            }
            DetailAnimationModel view = moduleViews[stage];
            if (view.getProgress() < 100.0) {
                view.advanceSharedBottle();
                bottles[bottle].idealisedProgress = view.getProgress();
            }
            if (view.getProgress() >= 100.0 &&
                moduleBottle[nextStage] == NO_BOTTLE) {
                moduleBottle[stage] = NO_BOTTLE;
                moduleBottle[nextStage] = bottle;
                bottles[bottle].idealisedStage = MACHINE_NAMES[nextStage];
                bottles[bottle].idealisedProgress = 0.0;
                view.finishSharedTransfer(bottle + 1);
                moduleViews[nextStage].beginSharedBottle(bottle + 1);
            }
        }

        private void advanceUnloader() {
            int bottle = moduleBottle[UNLOADER];
            if (bottle == NO_BOTTLE) {
                return;
            }
            DetailAnimationModel view = moduleViews[UNLOADER];
            if (view.getProgress() < 100.0) {
                view.advanceSharedBottle();
                bottles[bottle].idealisedProgress = view.getProgress();
            }
            if (view.getProgress() < 100.0) {
                return;
            }
            if (visualCompleted >= realCompleted) {
                view.setSharedWaitingPhase(
                    "WAITING FOR REAL VIZ_COMPLETED_BOTTLES ANCHOR"
                );
                return;
            }
            moduleBottle[UNLOADER] = NO_BOTTLE;
            visualCompleted++;
            bottles[bottle].idealisedStage = "VISUALLY COMPLETE";
            bottles[bottle].idealisedProgress = 100.0;
            view.finishSharedTransfer(bottle + 1);
        }

        private void advanceRotary() {
            if ("ENTRY".equals(rotaryPhase)) {
                advanceRotaryEntry();
            }
            else if ("ROTATING".equals(rotaryPhase)) {
                advanceRotaryIndex();
            }
            else if ("SETTLING".equals(rotaryPhase)) {
                advanceRotarySettling();
            }
            else if ("EXITING".equals(rotaryPhase)) {
                advanceRotaryExit();
            }
            else {
                rotaryPhase = "ENTRY";
                publishRotaryView("ENTRY - WAITING FOR NEXT BOTTLE");
            }
        }

        private void advanceRotaryEntry() {
            if (rotaryEntryBottle == NO_BOTTLE) {
                int conveyorBottle = moduleBottle[CONVEYOR];
                if (conveyorBottle != NO_BOTTLE &&
                    moduleViews[CONVEYOR].getProgress() >= 100.0 &&
                    rotaryBottle[0] == NO_BOTTLE) {
                    rotaryEntryBottle = conveyorBottle;
                    rotaryEntryProgress = 0.0;
                    bottles[conveyorBottle].idealisedStage = "ROTARY ENTRY";
                }
                else if (hasRotaryBottle() &&
                    rotaryBottle[ROTARY_STATIONS - 1] == NO_BOTTLE) {
                    rotaryPhase = "ROTATING";
                    rotaryAngle = 0.0;
                    publishRotaryView("ROTATING - DRAINING INDEX");
                    return;
                }
                else {
                    publishRotaryView("ENTRY - WAITING FOR SHARED QUEUE");
                    return;
                }
            }

            rotaryEntryProgress = Math.min(
                1.0,
                rotaryEntryProgress + 0.04
            );
            publishRotaryView(
                "ENTRY - BOTTLE #" + (rotaryEntryBottle + 1) +
                " MOVING INTO STATION 1"
            );
            if (rotaryEntryProgress >= 1.0) {
                rotaryBottle[0] = rotaryEntryBottle;
                moduleBottle[CONVEYOR] = NO_BOTTLE;
                moduleViews[CONVEYOR].finishSharedTransfer(
                    rotaryEntryBottle + 1
                );
                bottles[rotaryEntryBottle].idealisedStage =
                    "ROTARY STATION 1";
                bottles[rotaryEntryBottle].idealisedProgress = 0.0;
                rotaryEntered++;
                rotaryEntryBottle = NO_BOTTLE;
                rotaryEntryProgress = 0.0;
                rotaryPhase = "ROTATING";
                rotaryAngle = 0.0;
                publishRotaryView("ROTATING - INDEXING ONE STATION");
            }
        }

        private void advanceRotaryIndex() {
            if (rotaryBottle[ROTARY_STATIONS - 1] != NO_BOTTLE) {
                rotaryPhase = "SETTLING";
                rotarySettlingTicks = 10;
                publishRotaryView("SETTLING - WAITING FOR EXIT PATH");
                return;
            }
            rotaryAngle = Math.min(72.0, rotaryAngle + 4.0);
            publishRotaryView("ROTATING - INDEXING ONE STATION");
            if (rotaryAngle >= 72.0) {
                for (int station = ROTARY_STATIONS - 1;
                    station > 0;
                    station--) {
                    rotaryBottle[station] = rotaryBottle[station - 1];
                }
                rotaryBottle[0] = NO_BOTTLE;
                updateRotaryBottleRecords();
                rotaryAngle = 0.0;
                rotarySettlingTicks = 0;
                rotaryPhase = "SETTLING";
                publishRotaryView("SETTLING - INDEX POSITION LOCKING");
            }
        }

        private void advanceRotarySettling() {
            if (rotarySettlingTicks < 10) {
                rotarySettlingTicks++;
                publishRotaryView("SETTLING - INDEX POSITION LOCKING");
                return;
            }
            int last = ROTARY_STATIONS - 1;
            if (rotaryBottle[last] != NO_BOTTLE) {
                if (moduleBottle[FILLER_A] != NO_BOTTLE) {
                    publishRotaryView(
                        "SETTLING - WAITING FOR FILLER A CAPACITY"
                    );
                    return;
                }
                rotaryExitBottle = rotaryBottle[last];
                rotaryExitProgress = 0.0;
                rotaryPhase = "EXITING";
                publishRotaryView(
                    "EXITING - BOTTLE #" + (rotaryExitBottle + 1)
                );
                return;
            }
            int conveyorBottle = moduleBottle[CONVEYOR];
            if (conveyorBottle != NO_BOTTLE &&
                moduleViews[CONVEYOR].getProgress() >= 100.0 &&
                rotaryBottle[0] == NO_BOTTLE) {
                rotaryPhase = "ENTRY";
                rotaryEntryBottle = NO_BOTTLE;
                rotaryEntryProgress = 0.0;
                publishRotaryView("ENTRY - NEXT BOTTLE AVAILABLE");
            }
            else if (hasRotaryBottle()) {
                rotaryPhase = "ROTATING";
                rotaryAngle = 0.0;
                publishRotaryView("ROTATING - DRAINING INDEX");
            }
            else {
                rotaryPhase = "ENTRY";
                publishRotaryView("ENTRY - WAITING FOR SHARED QUEUE");
            }
        }

        private void advanceRotaryExit() {
            if (rotaryExitBottle == NO_BOTTLE) {
                rotaryPhase = "SETTLING";
                return;
            }
            rotaryExitProgress = Math.min(
                1.0,
                rotaryExitProgress + 0.04
            );
            publishRotaryView(
                "EXITING - BOTTLE #" + (rotaryExitBottle + 1) +
                " MOVING TO FILLER A"
            );
            if (rotaryExitProgress >= 1.0) {
                int bottle = rotaryExitBottle;
                rotaryBottle[ROTARY_STATIONS - 1] = NO_BOTTLE;
                rotaryExitBottle = NO_BOTTLE;
                rotaryExitProgress = 0.0;
                rotaryExited++;
                moduleBottle[FILLER_A] = bottle;
                bottles[bottle].idealisedStage = "Filler A";
                bottles[bottle].idealisedProgress = 0.0;
                moduleViews[FILLER_A].beginSharedBottle(bottle + 1);
                if (moduleBottle[CONVEYOR] != NO_BOTTLE &&
                    moduleViews[CONVEYOR].getProgress() >= 100.0) {
                    rotaryPhase = "ENTRY";
                }
                else if (hasRotaryBottle()) {
                    rotaryPhase = "ROTATING";
                }
                else {
                    rotaryPhase = "ENTRY";
                }
                publishRotaryView(
                    rotaryPhase + " - EXIT TRANSFER COMPLETE"
                );
            }
        }

        private void updateRotaryBottleRecords() {
            for (int station = 0; station < rotaryBottle.length; station++) {
                int bottle = rotaryBottle[station];
                if (bottle != NO_BOTTLE) {
                    bottles[bottle].idealisedStage =
                        "ROTARY STATION " + (station + 1);
                    bottles[bottle].idealisedProgress = 0.0;
                }
            }
        }

        private boolean hasRotaryBottle() {
            for (int station = 0; station < rotaryBottle.length; station++) {
                if (rotaryBottle[station] != NO_BOTTLE) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasWork() {
            if (nextBottle < bottles.length || rotaryEntryBottle != NO_BOTTLE ||
                rotaryExitBottle != NO_BOTTLE || hasRotaryBottle()) {
                return true;
            }
            for (int index = 0; index < moduleBottle.length; index++) {
                if (moduleBottle[index] != NO_BOTTLE) {
                    return true;
                }
            }
            return false;
        }

        private void updateLifecycleForCurrentWork() {
            for (int index = 0; index < moduleViews.length; index++) {
                boolean currentWork = index == ROTARY ?
                    (rotaryEntryBottle != NO_BOTTLE ||
                        rotaryExitBottle != NO_BOTTLE || hasRotaryBottle()) :
                    moduleBottle[index] != NO_BOTTLE;
                moduleViews[index].reconcileSharedLifecycle(
                    currentWork,
                    visualCompleted >= required && !hasWork()
                );
            }
        }

        private void publishRotaryView(String phaseText) {
            moduleViews[ROTARY].setSharedRotaryState(
                rotaryBottle,
                rotaryEntryBottle,
                rotaryExitBottle,
                rotaryPhase,
                rotaryEntryProgress,
                rotaryAngle,
                rotaryExitProgress,
                rotarySettlingTicks,
                rotaryEntered,
                rotaryExited,
                phaseText
            );
        }
    }

    /**
     * Shared animation state for a module detail view.
     *
     * The real frozen interface supplies only the Controller status. Every
     * numeric field below is therefore an IDEALISED visualisation variable.
     * Future Phase 2 telemetry can replace these fields without changing the
     * overview, dialog lifecycle or read-only Coordinator boundary.
     */
    static final class DetailAnimationModel {
        private static final int ROTARY_STATION_COUNT = 5;

        private final int machineIndex;
        private final double demoFillTarget;
        private final boolean[] rotaryStationOccupied =
            new boolean[ROTARY_STATION_COUNT];

        private boolean running;
        private double progress;
        private double conveyorBottlePosition;
        private double rollerAngle;
        private double rotaryAngle;
        private double rotaryEntryProgress;
        private double rotaryExitProgress;
        private int rotarySettlingTicks;
        private int rotaryBottlesEntered;
        private int rotaryBottlesExited;
        private String rotaryPhase;
        private double liquidALevel;
        private double liquidBLevel;
        private double tighteningAngle;
        private String phase;
        private VisualLifecycle lifecycle;
        private int currentBottleId;
        private int sharedRealStatus = -1;

        DetailAnimationModel(int index) {
            machineIndex = index;
            demoFillTarget = index == FILLER_A ? DEMO_LIQUID_A_PERCENT :
                (index == FILLER_B ? DEMO_LIQUID_B_PERCENT : 0.0);
            resetGeometry();
            phase = "WAITING FOR REAL STATUS";
            lifecycle = VisualLifecycle.IDLE;
            currentBottleId = 0;
        }

        void resetSharedBatch() {
            resetGeometry();
            running = false;
            currentBottleId = 0;
            lifecycle = VisualLifecycle.IDLE;
            phase = "NEW BATCH - SHARED MODEL RESET";
        }

        void setSharedRealStatus(int status) {
            sharedRealStatus = status;
            if (status == 4) {
                running = false;
                lifecycle = VisualLifecycle.FAULTED;
                phase = "FAULT - IDEALISED MOTION STOPPED IMMEDIATELY";
            }
            else if (status == 3) {
                lifecycle = VisualLifecycle.FINALISING;
                if (!running) {
                    phase = "DONE - FINALISING SHARED BATCH MODEL";
                }
            }
            else if (status == BUSY_STATUS) {
                lifecycle = VisualLifecycle.RUNNING;
            }
            else if (!running) {
                lifecycle = VisualLifecycle.IDLE;
            }
        }

        void beginSharedBottle(int bottleId) {
            resetGeometry();
            currentBottleId = bottleId;
            running = true;
            lifecycle = sharedRealStatus == 3 ?
                VisualLifecycle.FINALISING : VisualLifecycle.RUNNING;
            switch (machineIndex) {
                case LOADER:
                    phase = "BOTTLE #" + bottleId + " - GATE OPENING";
                    break;
                case CONVEYOR:
                    phase = "BOTTLE #" + bottleId + " - TRANSFERRING";
                    break;
                case FILLER_A:
                    phase = "BOTTLE #" + bottleId + " - FILLING A";
                    break;
                case FILLER_B:
                    phase = "BOTTLE #" + bottleId + " - ADDING B";
                    break;
                case LID:
                    phase = "BOTTLE #" + bottleId + " - SEPARATING LID";
                    break;
                case CAPPER:
                    phase = "BOTTLE #" + bottleId + " - HEAD DESCENDING";
                    break;
                case UNLOADER:
                    phase = "BOTTLE #" + bottleId + " - DISCHARGING";
                    break;
                default:
                    phase = "BOTTLE #" + bottleId + " - SHARED PROCESS";
                    break;
            }
        }

        void advanceSharedBottle() {
            if (!running || lifecycle == VisualLifecycle.FAULTED) {
                return;
            }
            switch (machineIndex) {
                case LOADER:
                    progress = Math.min(100.0, progress + 2.0);
                    phase = "BOTTLE #" + currentBottleId + " - " +
                        loaderPhase(progress);
                    break;
                case CONVEYOR:
                    progress = Math.min(100.0, progress + 1.7);
                    conveyorBottlePosition = progress / 100.0;
                    rollerAngle = (rollerAngle + 7.0) % 360.0;
                    phase = "BOTTLE #" + currentBottleId +
                        " - TRANSFERRING ON SHARED BELT";
                    break;
                case FILLER_A:
                    liquidALevel = Math.min(
                        DEMO_LIQUID_A_PERCENT,
                        liquidALevel + 1.5
                    );
                    progress = liquidALevel /
                        DEMO_LIQUID_A_PERCENT * 100.0;
                    phase = "BOTTLE #" + currentBottleId +
                        " - FILLING LIQUID A";
                    break;
                case FILLER_B:
                    liquidBLevel = Math.min(
                        DEMO_LIQUID_B_PERCENT,
                        liquidBLevel + 1.25
                    );
                    progress = liquidBLevel /
                        DEMO_LIQUID_B_PERCENT * 100.0;
                    phase = "BOTTLE #" + currentBottleId +
                        " - ADDING LIQUID B";
                    break;
                case LID:
                    progress = Math.min(100.0, progress + 2.0);
                    phase = "BOTTLE #" + currentBottleId + " - " +
                        lidPhase(progress);
                    break;
                case CAPPER:
                    progress = Math.min(100.0, progress + 2.0);
                    if (progress >= 30.0 && progress <= 72.0) {
                        tighteningAngle = (tighteningAngle + 9.0) % 360.0;
                    }
                    phase = "BOTTLE #" + currentBottleId + " - " +
                        capperPhase(progress);
                    break;
                case UNLOADER:
                    progress = Math.min(100.0, progress + 2.0);
                    phase = "BOTTLE #" + currentBottleId +
                        (progress < 100.0 ? " - DISCHARGING" :
                            " - AT COLLECTION POSITION");
                    break;
                default:
                    break;
            }
            if (progress >= 100.0) {
                running = false;
            }
        }

        void finishSharedTransfer(int bottleId) {
            running = false;
            progress = 100.0;
            currentBottleId = bottleId;
            phase = "BOTTLE #" + bottleId + " - TRANSFER COMPLETE";
        }

        void setSharedWaitingPhase(String text) {
            running = false;
            phase = text;
        }

        void reconcileSharedLifecycle(boolean hasWork, boolean batchComplete) {
            if (lifecycle == VisualLifecycle.FAULTED) {
                return;
            }
            if (batchComplete) {
                markSharedBatchComplete();
            }
            else if (sharedRealStatus == 3) {
                lifecycle = VisualLifecycle.FINALISING;
            }
            else if (hasWork || sharedRealStatus == BUSY_STATUS) {
                lifecycle = VisualLifecycle.RUNNING;
            }
            else {
                lifecycle = VisualLifecycle.IDLE;
            }
        }

        void markSharedBatchComplete() {
            if (lifecycle == VisualLifecycle.FAULTED) {
                return;
            }
            running = false;
            lifecycle = VisualLifecycle.COMPLETE;
            if (machineIndex == ROTARY) {
                phase = "COMPLETE - STABLE EMPTY TABLE";
            }
            else {
                phase = "COMPLETE - SHARED BATCH RECONCILED";
            }
        }

        void setSharedRotaryState(
            int[] stationBottle,
            int entryBottle,
            int exitBottle,
            String sharedPhase,
            double entryProgress,
            double angle,
            double exitProgress,
            int settlingTicks,
            int entered,
            int exited,
            String phaseText
        ) {
            for (int station = 0;
                station < rotaryStationOccupied.length;
                station++) {
                rotaryStationOccupied[station] =
                    stationBottle[station] >= 0;
            }
            rotaryPhase = sharedPhase;
            rotaryEntryProgress = entryProgress;
            rotaryAngle = angle;
            rotaryExitProgress = exitProgress;
            rotarySettlingTicks = settlingTicks;
            rotaryBottlesEntered = entered;
            rotaryBottlesExited = exited;
            currentBottleId = entryBottle >= 0 ? entryBottle + 1 :
                (exitBottle >= 0 ? exitBottle + 1 : currentBottleId);
            if ("ENTRY".equals(sharedPhase)) {
                progress = entryProgress * 100.0;
                running = entryBottle >= 0;
            }
            else if ("ROTATING".equals(sharedPhase)) {
                progress = angle / 72.0 * 100.0;
                running = true;
            }
            else if ("SETTLING".equals(sharedPhase)) {
                progress = Math.min(100.0, settlingTicks * 10.0);
                running = settlingTicks < 10;
            }
            else if ("EXITING".equals(sharedPhase)) {
                progress = exitProgress * 100.0;
                running = exitBottle >= 0;
            }
            else {
                running = false;
            }
            if (sharedRealStatus == 4) {
                running = false;
                lifecycle = VisualLifecycle.FAULTED;
                phase = "FAULT - IDEALISED MOTION STOPPED IMMEDIATELY";
            }
            else {
                lifecycle = sharedRealStatus == 3 ?
                    VisualLifecycle.FINALISING :
                    (running ? VisualLifecycle.RUNNING : lifecycle);
                phase = phaseText;
            }
        }

        double getProgress() {
            return progress;
        }

        double getConveyorBottlePosition() {
            return conveyorBottlePosition;
        }

        double getRollerAngle() {
            return rollerAngle;
        }

        double getRotaryAngle() {
            return rotaryAngle;
        }

        double getFillLevel() {
            return machineIndex == FILLER_B ?
                liquidBLevel : liquidALevel;
        }

        double getLiquidALevel() {
            return liquidALevel;
        }

        double getLiquidBLevel() {
            return liquidBLevel;
        }

        double getTotalFillLevel() {
            return liquidALevel + liquidBLevel;
        }

        double getDemoFillTarget() {
            return demoFillTarget;
        }

        double getTighteningAngle() {
            return tighteningAngle;
        }

        String getPhase() {
            return phase;
        }

        VisualLifecycle getLifecycle() {
            return lifecycle;
        }

        int getCurrentBottleId() {
            return currentBottleId;
        }

        boolean isRunning() {
            return running;
        }

        int getRotaryStationCount() {
            return ROTARY_STATION_COUNT;
        }

        boolean isRotaryStationOccupied(int station) {
            return station >= 0 && station < ROTARY_STATION_COUNT &&
                rotaryStationOccupied[station];
        }

        int getRotaryOccupiedCount() {
            int count = 0;
            for (int station = 0;
                station < ROTARY_STATION_COUNT;
                station++) {
                if (rotaryStationOccupied[station]) {
                    count++;
                }
            }
            return count;
        }

        double getRotaryEntryProgress() {
            return rotaryEntryProgress;
        }

        double getRotaryExitProgress() {
            return rotaryExitProgress;
        }

        int getRotaryBottlesEntered() {
            return rotaryBottlesEntered;
        }

        int getRotaryBottlesExited() {
            return rotaryBottlesExited;
        }

        String getRotaryPhase() {
            return rotaryPhase;
        }

        private void resetGeometry() {
            progress = 0.0;
            conveyorBottlePosition = 0.0;
            rollerAngle = 0.0;
            rotaryAngle = 0.0;
            rotaryEntryProgress = 0.0;
            rotaryExitProgress = 0.0;
            rotarySettlingTicks = 0;
            rotaryBottlesEntered = 0;
            rotaryBottlesExited = 0;
            rotaryPhase = "WAITING";
            for (int station = 0;
                station < ROTARY_STATION_COUNT;
                station++) {
                rotaryStationOccupied[station] = false;
            }
            liquidALevel = machineIndex == FILLER_B ?
                DEMO_LIQUID_A_PERCENT : 0.0;
            liquidBLevel = 0.0;
            tighteningAngle = 0.0;
        }

        private static String loaderPhase(double value) {
            if (value < 20.0) {
                return "GATE OPENING";
            }
            if (value < 55.0) {
                return "RELEASING BOTTLE";
            }
            if (value < 85.0) {
                return "TRANSFER TO OUTPUT";
            }
            if (value < 100.0) {
                return "GATE CLOSING";
            }
            return "COMPLETE - AWAITING REAL STATUS";
        }

        private static String lidPhase(double value) {
            if (value < 28.0) {
                return "SEPARATING LID";
            }
            if (value < 66.0) {
                return "FEEDING LID";
            }
            if (value < 100.0) {
                return "PLACING LID";
            }
            return "LID PLACED - AWAITING REAL STATUS";
        }

        private static String capperPhase(double value) {
            if (value < 30.0) {
                return "HEAD DESCENDING";
            }
            if (value < 42.0) {
                return "CONTACT";
            }
            if (value < 72.0) {
                return "TIGHTENING / ROTATING";
            }
            if (value < 100.0) {
                return "HEAD ASCENDING";
            }
            return "CAP SECURED - AWAITING REAL STATUS";
        }
    }

    /** Read-only dialog content sharing the same state as the overview. */
    static final class ModuleDetailPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final int DETAIL_WIDTH = 500;
        private static final int DETAIL_HEIGHT = 430;

        private final int machineIndex;
        private final DetailAnimationModel detailModel;
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
            detailModel = VISUAL_MODEL.getDetailModel(index);
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

        DetailAnimationModel getDetailModel() {
            return detailModel;
        }

        private void updateInformation() {
            String realStatusText = lastStatusReceived ?
                statusName(lastRealStatus) : "WAITING";
            realStatusValue.setText("Status: " + realStatusText);
            realStatusValue.setBackground(lastStatusReceived ?
                statusColor(lastRealStatus) : statusColor(-1));
            phaseValue.setText(
                wrapInformationText(
                    "Lifecycle: <b>" + detailModel.getLifecycle() +
                    "</b><br>Phase: <b>" + detailModel.getPhase() +
                    "</b><br>Shared visual bottle: <b>" +
                    (detailModel.getCurrentBottleId() > 0 ?
                        "#" + detailModel.getCurrentBottleId() : "--") +
                    "</b><br>Reconciliation: <b>" +
                    VISUAL_MODEL.getModeName() + "</b>"
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
                            "Gate and bottle motion: symbolic local cycle"
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
                            " / 72.0 deg<br>Occupied stations: " +
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
                            "Liquid A: " +
                            oneDecimal(detailModel.getLiquidALevel()) +
                            "%<br>Liquid B: 0.0%"
                        )
                    );
                    secondaryMetricValue.setText(
                        wrapInformationText(
                            "IDEALISED target A: " +
                            oneDecimal(DEMO_LIQUID_A_PERCENT) +
                            "%<br>Total fill: " +
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
                            "Liquid A retained: " +
                            oneDecimal(detailModel.getLiquidALevel()) +
                            "%<br>Liquid B added: " +
                            oneDecimal(detailModel.getLiquidBLevel()) + "%"
                        )
                    );
                    secondaryMetricValue.setText(
                        wrapInformationText(
                            "IDEALISED target B: " +
                            oneDecimal(DEMO_LIQUID_B_PERCENT) +
                            "%<br>Total fill: " +
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
                            "Shared visual bottle identity only; not real telemetry"
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
            StringBuilder text = new StringBuilder("Stations: ");
            for (int station = 0;
                station < detailModel.getRotaryStationCount();
                station++) {
                if (station > 0) {
                    text.append("  ");
                }
                text.append(station + 1).append('=')
                    .append(detailModel.isRotaryStationOccupied(station) ?
                        "BOTTLE" : "empty");
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

        /** Large mechanism renderer driven only by DetailAnimationModel. */
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
                    "Input and output overview graphics share one REAL status.",
                    78,
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
                    }

                    Point label = rotaryPoint(
                        centreX,
                        centreY,
                        150,
                        station,
                        0.0
                    );
                    g2.setColor(Color.WHITE);
                    g2.fillOval(label.x - 12, label.y - 12, 24, 24);
                    g2.setColor(new Color(45, 60, 75));
                    g2.drawOval(label.x - 12, label.y - 12, 24, 24);
                    g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                    ProductionLinePanel.drawCenteredText(
                        g2,
                        String.valueOf(station + 1),
                        label.x,
                        label.y + 5
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
                    "FIVE IDEALISED STATIONS - EMPTY BOTTLES ONLY",
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
                    150.0 - station * 72.0 - movementAngle
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
                if (lastRealStatus == 3) {
                    ProductionLinePanel.drawDoneTick(g2, 466, 48);
                }
                if (lastRealStatus == 4) {
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
