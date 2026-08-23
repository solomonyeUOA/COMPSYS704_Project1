import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
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
 * view is intentionally symbolic: it shows controller state and batch progress
 * without claiming to track individual bottle positions.
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
    private static final int ANIMATION_DELAY_MILLIS = 110;

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

    private ABSVisualisation() {
        frame = new JFrame(
            "Automated Bottling System - Symbolic Visualisation"
        );
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout(12, 10));

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
            "Phase 1 symbolic production-line overview",
            SwingConstants.CENTER
        );
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        subtitle.setForeground(new Color(75, 82, 92));
        subtitle.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        header.add(subtitle);
        frame.add(header, BorderLayout.NORTH);

        productionLinePanel = new ProductionLinePanel();
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
                    if (productionLinePanel.hasBusyMachine()) {
                        productionLinePanel.advanceAnimation();
                        productionLinePanel.repaint();
                    }
                }
            }
        );
        animationTimer.setCoalesce(true);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                animationTimer.stop();
                synchronized (ABSVisualisation.class) {
                    if (instance == ABSVisualisation.this) {
                        instance = null;
                    }
                }
            }
        });

        frame.setPreferredSize(new Dimension(1180, 730));
        frame.setMinimumSize(new Dimension(980, 650));
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
        requiredBottles = required;
        requiredBottlesReceived = true;
        printAndRefreshProgress();
    }

    public static synchronized void updateCompletedBottles(int completed) {
        if (completedBottlesReceived && completedBottles == completed) {
            return;
        }
        completedBottles = completed;
        completedBottlesReceived = true;
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
            "  Animation runs only while the matching real status is BUSY."
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

        progressLabel.setText(summary);
        int maximum = requiredReceived ? Math.max(1, required) :
            Math.max(1, completed);
        int value = completedReceived ? completed : 0;
        value = Math.max(0, Math.min(value, maximum));
        progressBar.setMaximum(maximum);
        progressBar.setValue(value);
        progressBar.setString(summary);
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

    /** Custom symbolic plant renderer; it never infers bottle locations. */
    static final class ProductionLinePanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final int DESIGN_WIDTH = 1160;
        private static final int DESIGN_HEIGHT = 420;

        private int animationPhase = 0;

        ProductionLinePanel() {
            setOpaque(true);
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(DESIGN_WIDTH, DESIGN_HEIGHT));
            setMinimumSize(new Dimension(880, 360));
            setToolTipText(
                "Symbolic Controller state only; no bottle positions tracked"
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
                "Coordinator-fed live state  |  symbolic Phase 1 view  |  no bottle-position tracking",
                DESIGN_WIDTH / 2,
                27
            );

            drawFlowConnections(g2);
            drawLoader(g2, 20, 142, 116, 164, statuses, received);
            drawConveyor(
                g2, 153, 178, 104, 94, "Input Conveyor", statuses, received
            );
            drawRotary(g2, 275, 122, 158, 192, statuses, received);
            drawFiller(
                g2, 452, 72, 128, 138, FILLER_A, "Filler A",
                new Color(42, 132, 210), statuses, received
            );
            drawFiller(
                g2, 452, 236, 128, 138, FILLER_B, "Filler B",
                new Color(124, 86, 190), statuses, received
            );
            drawLidLoader(g2, 607, 142, 112, 164, statuses, received);
            drawCapper(g2, 740, 142, 112, 164, statuses, received);
            drawConveyor(
                g2, 870, 178, 104, 94, "Output Conveyor", statuses, received
            );
            drawUnloader(g2, 992, 142, 146, 164, statuses, received);

            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g2.setColor(new Color(78, 86, 98));
            drawCenteredText(
                g2,
                "Input and output belt graphics share the single real VIZ_CONVEYOR_STATUS value.",
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
            int motion = busy ? triangleWave(animationPhase, 12) : 0;
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

            g2.fillRect(centreX - 5, y + 75, 10, 23);
            g2.setColor(busy ? statusColor(BUSY_STATUS) :
                new Color(118, 132, 146));
            g2.fillRoundRect(
                centreX - 19, y + 92 - motion / 3, 38, 10, 5, 5
            );
            g2.setColor(new Color(72, 83, 96));
            drawArrow(g2, centreX + 7, y + 105, centreX + 31, y + 105);
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
            int beltX = x + 12;
            int beltY = y + 36;
            int beltWidth = width - 24;

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
            int centreX = x + width / 2;
            int centreY = y + 92;
            int radius = 45;

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
            g2.fillOval(centreX - 7, centreY - 7, 14, 14);

            for (int station = 0; station < 5; station++) {
                double angle = -Math.PI / 2.0 + station *
                    (Math.PI * 2.0 / 5.0);
                int stationX = centreX + (int)(Math.cos(angle) * 32);
                int stationY = centreY + (int)(Math.sin(angle) * 32);
                g2.setColor(new Color(248, 250, 252));
                g2.fillOval(stationX - 6, stationY - 6, 12, 12);
                g2.setColor(new Color(77, 90, 104));
                g2.drawOval(stationX - 6, stationY - 6, 12, 12);
            }

            double indicatorAngle = busy ?
                Math.toRadians((animationPhase * 12) % 360 - 90) :
                -Math.PI / 2.0;
            int indicatorX = centreX + (int)(Math.cos(indicatorAngle) * 39);
            int indicatorY = centreY + (int)(Math.sin(indicatorAngle) * 39);
            g2.setColor(busy ? statusColor(BUSY_STATUS) :
                new Color(103, 116, 130));
            g2.setStroke(new BasicStroke(3.0f));
            g2.draw(new Line2D.Double(
                centreX, centreY, indicatorX, indicatorY
            ));
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            g2.setColor(new Color(79, 88, 99));
            drawCenteredText(g2, "5 symbolic stations", centreX, y + 148);
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
            int tankX = x + 25;
            int tankY = y + 37;
            int tankWidth = 51;
            int tankHeight = 38;

            g2.setColor(new Color(228, 234, 240));
            g2.fillRoundRect(tankX, tankY, tankWidth, tankHeight, 8, 8);
            g2.setColor(liquidColor);
            g2.fillRect(tankX + 4, tankY + 22, tankWidth - 8, 11);
            g2.setColor(new Color(71, 84, 98));
            g2.drawRoundRect(tankX, tankY, tankWidth, tankHeight, 8, 8);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g2.setColor(new Color(54, 65, 78));
            drawCenteredText(
                g2, index == FILLER_A ? "A" : "B",
                tankX + tankWidth / 2, tankY + 18
            );

            int nozzleX = tankX + tankWidth + 13;
            g2.setColor(new Color(72, 83, 96));
            g2.fillRect(tankX + tankWidth, tankY + 16, 15, 6);
            g2.fillRect(nozzleX, tankY + 18, 6, 27);
            g2.fillRect(nozzleX - 4, tankY + 43, 14, 5);

            if (busy) {
                int dropOffset = (animationPhase * 3) % 17;
                g2.setColor(liquidColor);
                g2.fill(new Ellipse2D.Double(
                    nozzleX - 1, tankY + 49 + dropOffset, 8, 11
                ));
            }
            else {
                g2.setColor(new Color(153, 164, 176));
                g2.drawOval(nozzleX, tankY + 52, 6, 8);
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
            int motion = busy ? triangleWave(animationPhase, 17) : 0;

            g2.setColor(new Color(194, 204, 215));
            g2.fillRoundRect(x + 20, y + 40, 32, 66, 8, 8);
            g2.setColor(new Color(72, 84, 98));
            g2.drawRoundRect(x + 20, y + 40, 32, 66, 8, 8);
            for (int lidY = y + 50; lidY <= y + 89; lidY += 13) {
                g2.drawOval(x + 25, lidY, 22, 6);
            }

            int headY = y + 47 + motion;
            g2.fillRect(x + 73, y + 38, 5, 42 + motion);
            g2.setColor(busy ? statusColor(BUSY_STATUS) :
                new Color(118, 132, 146));
            g2.fillRoundRect(x + 62, headY + 34, 27, 10, 5, 5);
            g2.setColor(new Color(72, 84, 98));
            g2.drawLine(x + 58, y + 105, x + 93, y + 105);
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
            int motion = busy ? triangleWave(animationPhase, 14) : 0;
            int centreX = x + width / 2;
            int headY = y + 48 + motion;

            g2.setColor(new Color(76, 89, 103));
            g2.fillRect(centreX - 3, y + 38, 6, 34 + motion);
            g2.setColor(busy ? statusColor(BUSY_STATUS) :
                new Color(143, 156, 169));
            g2.fillRoundRect(centreX - 22, headY + 19, 44, 18, 7, 7);
            g2.setColor(new Color(72, 84, 98));
            g2.drawRoundRect(centreX - 22, headY + 19, 44, 18, 7, 7);
            g2.drawLine(centreX - 30, y + 109, centreX + 30, y + 109);

            if (busy) {
                int startAngle = (animationPhase * 18) % 360;
                g2.setColor(statusColor(BUSY_STATUS));
                g2.setStroke(new BasicStroke(2.0f));
                g2.draw(new Arc2D.Double(
                    centreX - 29, headY + 12, 58, 32,
                    startAngle, 115, Arc2D.OPEN
                ));
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

            g2.setColor(new Color(80, 93, 107));
            g2.setStroke(new BasicStroke(3.0f));
            g2.drawLine(x + 20, y + 57, x + 62, y + 100);
            g2.drawLine(x + 20, y + 75, x + 50, y + 106);
            g2.drawLine(x + 50, y + 106, x + 117, y + 106);

            int offset = busy ? (animationPhase * 4) % 20 : 0;
            g2.setColor(busy ? statusColor(BUSY_STATUS) :
                new Color(128, 141, 154));
            for (int arrowX = x + 53 + offset;
                arrowX < x + 112;
                arrowX += 20) {
                drawArrow(g2, arrowX, y + 93, arrowX + 12, y + 93);
            }
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            g2.setColor(new Color(79, 88, 99));
            drawCenteredText(
                g2, "collection output", x + width / 2, y + 121
            );
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
            return received[index] && statuses[index] == BUSY_STATUS;
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
}
