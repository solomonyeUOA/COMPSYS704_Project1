import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.util.HashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** Read-only animated topology built entirely from monitoring snapshots. */
public final class FaultMonitoringViewV2_1 extends JPanel {
    private static final Color TEXT = new Color(30, 37, 35);
    private static final Color MUTED = new Color(102, 111, 108);
    private static final Color BORDER = new Color(205, 212, 209);
    private static final Color GREEN = new Color(24, 124, 80);
    private static final Color BLUE = new Color(25, 104, 163);
    private static final Color AMBER = new Color(178, 105, 8);
    private static final Color RED = new Color(183, 52, 48);
    private static final Color DISABLED = new Color(148, 155, 152);

    private final TopologyCanvas canvas = new TopologyCanvas();
    private final JTextArea selectedDetails = new JTextArea();
    private FaultMonitoringStateV2_1.Snapshot snapshot;
    private boolean chinese;

    public FaultMonitoringViewV2_1() {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setBackground(Color.WHITE);
        canvas.setPreferredSize(new Dimension(850, 345));
        add(canvas, BorderLayout.CENTER);

        selectedDetails.setEditable(false);
        selectedDetails.setLineWrap(true);
        selectedDetails.setWrapStyleWord(true);
        selectedDetails.setRows(5);
        selectedDetails.setForeground(TEXT);
        selectedDetails.setBackground(new Color(248, 249, 249));
        selectedDetails.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        selectedDetails.setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
        JScrollPane scroll = new JScrollPane(selectedDetails);
        scroll.setPreferredSize(new Dimension(200, 105));
        scroll.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(BORDER), "Selected component"
        ));
        add(scroll, BorderLayout.SOUTH);
    }

    public void updateSnapshot(
        FaultMonitoringStateV2_1.Snapshot current,
        boolean useChinese
    ) {
        snapshot = current;
        chinese = useChinese;
        canvas.setSnapshot(current);
        updateSelectedDetails();
    }

    private void updateSelectedDetails() {
        if (snapshot == null) {
            selectedDetails.setText("-");
            return;
        }
        FaultMonitoringStateV2_1.ComponentSnapshot selected =
            find(snapshot, canvas.getSelectedName());
        if (selected == null) {
            selectedDetails.setText("-");
            return;
        }
        StringBuilder text = new StringBuilder();
        append(text, t("Component", "组件"), selected.name);
        append(text, t("Owner", "负责人"), selected.owner);
        append(text, t("State", "状态"),
            FaultMonitoringPresentationV2_1.displayState(selected, snapshot));
        append(text, t("Health / link", "健康 / 链路"), selected.heartbeat);
        append(text, t("Last seen", "最近活动"), formatAge(selected.lastSeenAgeMs));
        append(text, t("Last healthy", "最近健康"), formatAge(selected.lastHealthyAgeMs));
        append(text, t("Detail", "详情"), selected.detail);
        if (FaultMonitoringPresentationV2_1.isFaultSource(selected, snapshot)) {
            append(text, t("Fault type", "故障类型"), snapshot.faultCode);
            append(text, t("Fault age", "故障持续"),
                formatDuration(snapshot.capturedAtMs - snapshot.stateEnteredAtMs));
            append(text, t("Retry", "重试"), snapshot.attempt + " / " +
                snapshot.maximumAttempts);
            append(text, t("Recovery", "恢复状态"), snapshot.decision);
            append(text, t("Evidence", "验证证据"), snapshot.latestEvidence);
        }
        selectedDetails.setText(text.toString());
        selectedDetails.setCaretPosition(0);
    }

    private String t(String english, String chineseText) {
        return chinese ? chineseText : english;
    }

    private static void append(StringBuilder text, String name, String value) {
        if (text.length() > 0) {
            text.append("    ");
        }
        text.append(name).append(": ").append(value(value)).append('\n');
    }

    private static String value(String value) {
        return value == null || value.trim().length() == 0 ? "-" : value;
    }

    private static String formatAge(long ageMs) {
        if (ageMs < 0L) return "Never";
        if (ageMs < 1000L) return "<1 s";
        return (ageMs / 1000L) + " s ago";
    }

    private static String formatDuration(long durationMs) {
        if (durationMs < 1000L) return "<1 s";
        return (durationMs / 1000L) + " s";
    }

    private static FaultMonitoringStateV2_1.ComponentSnapshot find(
        FaultMonitoringStateV2_1.Snapshot current,
        String name
    ) {
        if (current == null || name == null) return null;
        for (FaultMonitoringStateV2_1.ComponentSnapshot component :
            current.components) {
            if (name.equals(component.name)) return component;
        }
        return null;
    }

    private final class TopologyCanvas extends JComponent {
        private final Map<String, Rectangle> nodeBounds =
            new HashMap<String, Rectangle>();
        private FaultMonitoringStateV2_1.Snapshot current;
        private String selectedName = FaultMonitoringStateV2_1.SUPERVISOR;
        private int phase;

        TopologyCanvas() {
            setOpaque(true);
            setBackground(Color.WHITE);
            MouseAdapter mouse = new MouseAdapter() {
                public void mouseClicked(MouseEvent event) {
                    String hit = nodeAt(event.getPoint());
                    if (hit != null) {
                        selectedName = hit;
                        updateSelectedDetails();
                        repaint();
                    }
                }

                public void mouseMoved(MouseEvent event) {
                    setCursor(nodeAt(event.getPoint()) == null ?
                        Cursor.getDefaultCursor() :
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        void setSnapshot(FaultMonitoringStateV2_1.Snapshot next) {
            current = next;
            phase = (phase + 1) % 48;
            repaint();
        }

        String getSelectedName() {
            return selectedName;
        }

        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (current == null) return;
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
            layoutNodes();
            drawConnections(g);
            for (FaultMonitoringStateV2_1.ComponentSnapshot component :
                current.components) {
                Rectangle box = nodeBounds.get(component.name);
                if (box != null) drawNode(g, component, box);
            }
            g.dispose();
        }

        private void layoutNodes() {
            nodeBounds.clear();
            int margin = 24;
            int gap = 26;
            int width = Math.max(170,
                (getWidth() - margin * 2 - gap * 2) / 3);
            int height = 76;
            int usableHeight = Math.max(240, getHeight() - 28);
            int rowGap = Math.max(22, (usableHeight - height * 3) / 2);
            int[] x = {margin, margin + width + gap,
                margin + (width + gap) * 2};
            int[] y = {14, 14 + height + rowGap,
                14 + (height + rowGap) * 2};
            put(FaultMonitoringStateV2_1.M1_LINK, x[0], y[0], width, height);
            put(FaultMonitoringStateV2_1.GUI_WORKER, x[1], y[0], width, height);
            put(FaultMonitoringStateV2_1.M2_LINK, x[2], y[0], width, height);
            put(FaultMonitoringStateV2_1.ROTARY_CONTROLLER, x[0], y[1], width, height);
            put(FaultMonitoringStateV2_1.SUPERVISOR, x[1], y[1], width, height);
            put(FaultMonitoringStateV2_1.LID_CONTROLLER, x[2], y[1], width, height);
            put(FaultMonitoringStateV2_1.ROTARY_PLANT, x[0], y[2], width, height);
            put("Filler A / B and Capper", x[1], y[2], width, height);
            put(FaultMonitoringStateV2_1.LID_PLANT, x[2], y[2], width, height);
        }

        private void put(String name, int x, int y, int width, int height) {
            nodeBounds.put(name, new Rectangle(x, y, width, height));
        }

        private void drawConnections(Graphics2D g) {
            drawConnection(g, FaultMonitoringStateV2_1.M1_LINK,
                FaultMonitoringStateV2_1.SUPERVISOR, "PROTOCOL");
            drawConnection(g, FaultMonitoringStateV2_1.SUPERVISOR,
                FaultMonitoringStateV2_1.M2_LINK, "PROTOCOL");
            drawConnection(g, FaultMonitoringStateV2_1.SUPERVISOR,
                FaultMonitoringStateV2_1.ROTARY_CONTROLLER, "MONITOR");
            drawConnection(g, FaultMonitoringStateV2_1.ROTARY_CONTROLLER,
                FaultMonitoringStateV2_1.ROTARY_PLANT, "CONTROL");
            drawConnection(g, FaultMonitoringStateV2_1.SUPERVISOR,
                FaultMonitoringStateV2_1.LID_CONTROLLER, "MONITOR");
            drawConnection(g, FaultMonitoringStateV2_1.LID_CONTROLLER,
                FaultMonitoringStateV2_1.LID_PLANT, "CONTROL");
            drawConnection(g, FaultMonitoringStateV2_1.GUI_WORKER,
                FaultMonitoringStateV2_1.SUPERVISOR, "MONITOR");
        }

        private void drawConnection(
            Graphics2D g,
            String fromName,
            String toName,
            String type
        ) {
            Rectangle from = nodeBounds.get(fromName);
            Rectangle to = nodeBounds.get(toName);
            FaultMonitoringStateV2_1.ComponentSnapshot fromState =
                find(current, fromName);
            FaultMonitoringStateV2_1.ComponentSnapshot toState =
                find(current, toName);
            if (from == null || to == null || fromState == null || toState == null) {
                return;
            }
            Point start = center(from);
            Point end = center(to);
            boolean fault = unhealthy(fromState) || unhealthy(toState);
            boolean active = !fault && connectionActive(fromState, toState, type);
            g.setStroke("MONITOR".equals(type) ?
                new BasicStroke(1.4f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND, 10f, new float[] {4f, 5f}, 0f) :
                new BasicStroke(1.8f));
            g.setColor(fault ? RED : active ? BLUE : BORDER);
            g.draw(new Line2D.Double(start.x, start.y, end.x, end.y));
            if (active) {
                double progress = (phase % 24) / 23.0;
                int x = (int) (start.x + (end.x - start.x) * progress);
                int y = (int) (start.y + (end.y - start.y) * progress);
                g.fillOval(x - 3, y - 3, 6, 6);
            }
        }

        private boolean connectionActive(
            FaultMonitoringStateV2_1.ComponentSnapshot from,
            FaultMonitoringStateV2_1.ComponentSnapshot to,
            String type
        ) {
            if ("PROTOCOL".equals(type)) {
                FaultMonitoringStateV2_1.ComponentSnapshot peer =
                    "M3".equals(from.owner) ? to : from;
                return "OBSERVED".equals(peer.state) && peer.lastSeenAgeMs < 1200L;
            }
            if ("CONTROL".equals(type)) {
                String state = FaultMonitoringPresentationV2_1.displayState(
                    from, current
                );
                return from.lastSeenAgeMs < 1200L && to.lastSeenAgeMs < 1200L &&
                    (state.contains("RUNNING") || state.contains("BUSY") ||
                        state.contains("RECOVERING"));
            }
            return from.lastSeenAgeMs < 1200L && to.lastSeenAgeMs < 1200L &&
                "RESPONSIVE".equals(from.heartbeat) &&
                "RESPONSIVE".equals(to.heartbeat);
        }

        private void drawNode(
            Graphics2D g,
            FaultMonitoringStateV2_1.ComponentSnapshot component,
            Rectangle box
        ) {
            String state = FaultMonitoringPresentationV2_1.displayState(
                component, current
            );
            Color color = nodeColor(component, state);
            boolean selected = component.name.equals(selectedName);
            boolean pulse = pulseEnabled(component, state);
            if (pulse) {
                int alpha = 24 + (phase % 12) * 3;
                g.setColor(new Color(color.getRed(), color.getGreen(),
                    color.getBlue(), Math.min(alpha, 60)));
                int spread = 3 + phase % 5;
                g.fillRoundRect(box.x - spread, box.y - spread,
                    box.width + spread * 2, box.height + spread * 2, 13, 13);
            }
            g.setColor(nodeFill(component, state));
            g.fillRoundRect(box.x, box.y, box.width, box.height, 10, 10);
            g.setStroke(new BasicStroke(selected ? 2.5f : 1.5f));
            g.setColor(selected ? BLUE : color);
            g.drawRoundRect(box.x, box.y, box.width, box.height, 10, 10);

            g.setColor(TEXT);
            Font nameFont = fittedFont(g, component.name,
                new Font(Font.SANS_SERIF, Font.BOLD, 13), box.width - 24);
            g.setFont(nameFont);
            drawCentered(g, component.name, box, box.y + 22);
            g.setColor(color);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            drawCentered(g, state, box, box.y + 44);
            g.setColor(MUTED);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            drawCentered(g, healthText(component), box, box.y + 62);

            if (hasHeartbeat(component) && component.lastSeenAgeMs < 600L) {
                int radius = 4 + phase % 4;
                g.setColor(new Color(GREEN.getRed(), GREEN.getGreen(),
                    GREEN.getBlue(), 120));
                g.drawOval(box.x + box.width - 18 - radius / 2,
                    box.y + 9 - radius / 2, radius, radius);
                g.fillOval(box.x + box.width - 16, box.y + 11, 4, 4);
            }
        }

        private Color nodeColor(
            FaultMonitoringStateV2_1.ComponentSnapshot component,
            String state
        ) {
            if ("NOT MONITORED".equals(component.state)) return DISABLED;
            if ("UNRESPONSIVE".equals(component.heartbeat) ||
                state.contains("FAULT") || state.contains("FAILED")) return RED;
            if (state.contains("RECOVERING") || state.contains("RUNNING") ||
                state.contains("BUSY")) return BLUE;
            if ("LATE".equals(component.heartbeat) ||
                state.contains("WAITING") || state.contains("ISOLATED")) return AMBER;
            if ("RESPONSIVE".equals(component.heartbeat) ||
                state.contains("READY") || state.contains("VERIFIED")) return GREEN;
            return MUTED;
        }

        private Color nodeFill(
            FaultMonitoringStateV2_1.ComponentSnapshot component,
            String state
        ) {
            Color color = nodeColor(component, state);
            int blend = "NOT MONITORED".equals(component.state) ? 244 : 248;
            return new Color(
                (color.getRed() + blend * 5) / 6,
                (color.getGreen() + blend * 5) / 6,
                (color.getBlue() + blend * 5) / 6
            );
        }

        private boolean pulseEnabled(
            FaultMonitoringStateV2_1.ComponentSnapshot component,
            String state
        ) {
            if ("UNRESPONSIVE".equals(component.heartbeat) ||
                "NOT MONITORED".equals(component.state)) return false;
            if (state.contains("FAULT") || state.contains("RECOVERING") ||
                state.contains("RUNNING") || state.contains("BUSY")) return true;
            return hasHeartbeat(component) && component.lastSeenAgeMs < 600L;
        }

        private boolean hasHeartbeat(
            FaultMonitoringStateV2_1.ComponentSnapshot component
        ) {
            return "RESPONSIVE".equals(component.heartbeat) ||
                "LATE".equals(component.heartbeat) ||
                "UNRESPONSIVE".equals(component.heartbeat);
        }

        private boolean unhealthy(
            FaultMonitoringStateV2_1.ComponentSnapshot component
        ) {
            String state = FaultMonitoringPresentationV2_1.displayState(
                component, current
            );
            return "UNRESPONSIVE".equals(component.heartbeat) ||
                state.contains("FAULT") || state.contains("FAILED");
        }

        private String healthText(
            FaultMonitoringStateV2_1.ComponentSnapshot component
        ) {
            if ("NO HEARTBEAT CONTRACT".equals(component.heartbeat)) {
                return "OBSERVED".equals(component.state) ?
                    "Protocol observed" : "No heartbeat contract";
            }
            return component.heartbeat;
        }

        private String nodeAt(Point point) {
            for (Map.Entry<String, Rectangle> entry : nodeBounds.entrySet()) {
                if (entry.getValue().contains(point)) return entry.getKey();
            }
            return null;
        }

        private Point center(Rectangle rectangle) {
            return new Point(rectangle.x + rectangle.width / 2,
                rectangle.y + rectangle.height / 2);
        }

        private Font fittedFont(
            Graphics2D g,
            String text,
            Font base,
            int maximumWidth
        ) {
            Font font = base;
            while (font.getSize() > 10 &&
                g.getFontMetrics(font).stringWidth(text) > maximumWidth) {
                font = font.deriveFont((float) (font.getSize() - 1));
            }
            return font;
        }

        private void drawCentered(
            Graphics2D g,
            String text,
            Rectangle box,
            int baseline
        ) {
            FontMetrics metrics = g.getFontMetrics();
            int x = box.x + Math.max(8,
                (box.width - metrics.stringWidth(text)) / 2);
            g.drawString(text, x, baseline);
        }
    }
}
