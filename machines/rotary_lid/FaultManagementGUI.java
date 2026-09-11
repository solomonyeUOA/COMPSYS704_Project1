import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/** Starts the native Swing M3 fault-management dashboard. */
public final class FaultManagementGUI {
    private static boolean started;

    private FaultManagementGUI() {
    }

    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("M3 fault-management GUI disabled in headless mode");
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                installLookAndFeel();
                final DashboardFrame frame = new DashboardFrame();
                frame.addWindowListener(new WindowAdapter() {
                    public void windowClosed(WindowEvent event) {
                        synchronized (FaultManagementGUI.class) {
                            started = false;
                        }
                    }
                });
                frame.setVisible(true);
                frame.setAlwaysOnTop(true);
                frame.toFront();
                frame.requestFocus();
                new Timer(1200, event -> {
                    frame.setAlwaysOnTop(false);
                    ((Timer) event.getSource()).stop();
                }).start();
            }
        });
    }

    private static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        }
        catch (Exception ignored) {
            // Swing's cross-platform appearance remains usable.
        }
    }

    private static final class DashboardFrame extends JFrame {
        private static final Color INK = new Color(32, 34, 37);
        private static final Color GREEN = new Color(24, 126, 85);
        private static final Color TEAL = new Color(0, 113, 164);
        private static final Color RED = new Color(194, 61, 53);
        private static final Color AMBER = new Color(184, 109, 5);
        private static final Color SURFACE = new Color(247, 247, 249);
        private static final Color BORDER = new Color(218, 219, 222);

        private final JLabel title = label("Fault-Tolerance Supervisor", 25, true);
        private final JLabel subtitle = label("COMPSYS 704  /  MEMBER 3 IP", 11, true);
        private final JLabel stateBadge = badge("LIVE", GREEN);
        private final JLabel decisionTitle = label("Monitoring", 24, true);
        private final JLabel decisionContext = label("No active fault", 13, false);
        private final JLabel nextAction = label("Waiting for a controller event", 15, true);
        private final JLabel result = label(" ", 12, false);
        private final JLabel recoveryTitle = label("Evidence-gated recovery", 19, true);
        private final JLabel processTitle = label("Bottle journey and machine state", 19, true);
        private final JLabel authority = label("M1 owns HOLD / RESUME", 11, true);
        private final JTextArea incident = textArea();
        private final JTextArea history = textArea();
        private final ProcessPanel process = new ProcessPanel();
        private final JPanel recovery = new JPanel(new GridLayout(1, 5, 9, 0));
        private final JTabbedPane details = new JTabbedPane();
        private final JComboBox<String> faults = new JComboBox<String>(new String[] {
            "ALIGNMENT_TIMEOUT", "MOTOR_STALL", "POSITION_SENSOR_FAILURE",
            "MAGAZINE_EMPTY", "PICK_TIMEOUT", "PLACEMENT_TIMEOUT",
            "LID_SENSOR_FAULT", "ARRIVAL_TIMEOUT", "DEPARTURE_TIMEOUT",
            "PHOTO_EYE_FAILURE", "POSITION_CONFLICT"
        });
        private final JButton inject = button("Inject fault");
        private final JButton safeStop = button("Confirm safe stop");
        private final JButton controllerEvidence = button("Submit controller evidence");
        private final JButton manualEvidence = button("Record reconciliation");
        private final JButton resume = button("Simulate M1 resume");
        private final JButton reset = button("Reset");
        private final JButton language = button("中文");
        private final Timer timer;
        private boolean chinese;

        DashboardFrame() {
            super("M3 Fault-Tolerance Supervisor");
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setMinimumSize(new Dimension(820, 640));
            setSize(new Dimension(1240, 820));
            setLocationRelativeTo(null);
            setContentPane(buildContent());
            addComponentListener(new ComponentAdapter() {
                public void componentResized(ComponentEvent event) {
                    updateResponsiveLayout();
                }
            });
            wireActions();
            updateResponsiveLayout();
            timer = new Timer(200, event -> refresh());
            timer.start();
            refresh();
        }

        private JPanel buildContent() {
            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(new Color(239, 239, 242));
            root.add(buildHeader(), BorderLayout.NORTH);

            JPanel body = new JPanel();
            body.setOpaque(false);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setBorder(BorderFactory.createEmptyBorder(16, 18, 20, 18));
            body.add(buildDecision());
            body.add(Box.createVerticalStrut(14));
            body.add(section("LIVE PROCESS", processTitle, process));
            body.add(Box.createVerticalStrut(14));
            body.add(buildRecovery());
            body.add(Box.createVerticalStrut(14));
            body.add(buildLowerArea());

            JScrollPane scroll = new JScrollPane(body);
            scroll.setBorder(null);
            scroll.getVerticalScrollBar().setUnitIncrement(18);
            scroll.getViewport().setBackground(new Color(239, 239, 242));
            root.add(scroll, BorderLayout.CENTER);
            return root;
        }

        private JPanel buildHeader() {
            JPanel header = new JPanel(new BorderLayout(18, 0));
            header.setBackground(new Color(252, 252, 253));
            header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                BorderFactory.createEmptyBorder(13, 20, 13, 20)
            ));
            subtitle.setForeground(new Color(104, 106, 110));
            title.setForeground(INK);
            JPanel names = new JPanel();
            names.setOpaque(false);
            names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
            names.add(subtitle);
            names.add(Box.createVerticalStrut(3));
            names.add(title);
            header.add(names, BorderLayout.WEST);

            JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 9, 8));
            controls.setOpaque(false);
            controls.add(language);
            controls.add(reset);
            controls.add(stateBadge);
            header.add(controls, BorderLayout.EAST);
            return header;
        }

        private JPanel buildDecision() {
            JPanel panel = card(new BorderLayout(20, 0));
            panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 4, 0, 0, AMBER),
                    BorderFactory.createEmptyBorder(17, 19, 17, 19)
                )
            ));
            JPanel fault = new JPanel();
            fault.setOpaque(false);
            fault.setLayout(new BoxLayout(fault, BoxLayout.Y_AXIS));
            fault.add(decisionTitle);
            fault.add(Box.createVerticalStrut(4));
            decisionContext.setForeground(new Color(91, 107, 100));
            fault.add(decisionContext);
            panel.add(fault, BorderLayout.WEST);

            JPanel action = new JPanel();
            action.setOpaque(false);
            action.setLayout(new BoxLayout(action, BoxLayout.Y_AXIS));
            nextAction.setForeground(INK);
            action.add(nextAction);
            result.setForeground(RED);
            action.add(Box.createVerticalStrut(5));
            action.add(result);
            panel.add(action, BorderLayout.CENTER);
            return panel;
        }

        private JPanel buildRecovery() {
            recovery.setOpaque(false);
            JPanel content = new JPanel(new BorderLayout(12, 14));
            content.setOpaque(false);
            JPanel heading = new JPanel(new BorderLayout());
            heading.setOpaque(false);
            heading.add(recoveryTitle, BorderLayout.WEST);
            authority.setForeground(new Color(91, 107, 100));
            heading.add(authority, BorderLayout.EAST);
            content.add(heading, BorderLayout.NORTH);
            content.add(recovery, BorderLayout.CENTER);
            return section("RECOVERY PATH", content);
        }

        private Component buildLowerArea() {
            details.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            details.addTab("Decision & Evidence", paddedScroll(incident));
            details.addTab("Test Mode", buildTestControls());
            details.addTab("Event History", paddedScroll(history));
            details.setPreferredSize(new Dimension(1000, 285));
            return details;
        }

        private JPanel buildTestControls() {
            JPanel content = new JPanel(new GridBagLayout());
            content.setBackground(Color.WHITE);
            content.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(0, 0, 9, 0);
            faults.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            content.add(faults, c);
            c.gridy++;
            content.add(inject, c);
            c.gridy++;
            content.add(safeStop, c);
            c.gridy++;
            content.add(controllerEvidence, c);
            c.gridy++;
            content.add(manualEvidence, c);
            c.gridy++;
            content.add(resume, c);
            c.gridy++;
            c.weighty = 1;
            content.add(Box.createVerticalGlue(), c);
            return content;
        }

        private JScrollPane paddedScroll(JTextArea area) {
            JScrollPane scroll = new JScrollPane(area);
            scroll.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            scroll.getViewport().setBackground(SURFACE);
            return scroll;
        }

        private void wireActions() {
            inject.addActionListener(event -> act("inject"));
            safeStop.addActionListener(event -> act("safe-stop"));
            controllerEvidence.addActionListener(event -> act("controller-evidence"));
            manualEvidence.addActionListener(event -> act("manual-evidence"));
            resume.addActionListener(event -> act("resume"));
            reset.addActionListener(event -> act("reset"));
            language.addActionListener(event -> {
                chinese = !chinese;
                applyLanguage();
                refresh();
            });
        }

        private void act(String action) {
            result.setText("");
            boolean accepted;
            try {
                accepted = FaultGuiActionsV2_1.perform(
                    action, String.valueOf(faults.getSelectedItem())
                );
            }
            catch (RuntimeException exception) {
                accepted = false;
                result.setText(exception.getMessage());
            }
            if (accepted) {
                result.setForeground(GREEN);
                result.setText(chinese ? "操作已接受" : "Action accepted");
            }
            else if (result.getText().trim().length() == 0) {
                result.setForeground(RED);
                result.setText(chinese ? "当前状态不允许此操作" :
                    "Action rejected by the supervisor");
            }
            refresh();
        }

        private void refresh() {
            String state = FaultSupervisorStateV2_1.stateName();
            String fault = value(FaultSupervisorStateV2_1.activeFaultCode(),
                chinese ? "无活动故障" : "No active fault");
            String subsystem = value(FaultSupervisorStateV2_1.activeSubsystem(), "-");
            String severity = value(FaultSupervisorStateV2_1.activeSeverity(), "NORMAL");
            decisionTitle.setText("IDLE".equals(state) ?
                (chinese ? "正在监控" : "Monitoring") : human(fault));
            decisionContext.setText(subsystem + "  /  " + severity + "  /  " +
                value(FaultSupervisorStateV2_1.activeBottleId(), "-") + "  /  " + state);
            nextAction.setText(nextAction(state));
            stateBadge.setText(state.replace('_', ' '));
            stateBadge.setBackground(stateColor(state));

            incident.setText(incidentText(state));
            history.setText(historyText());
            process.setState(subsystem, state, fault);
            updateRecovery(state);

            boolean testMode = Boolean.getBoolean("m3.testMode");
            inject.setEnabled(testMode && FaultGuiPolicyV2_1.canInject(state));
            safeStop.setEnabled(testMode && FaultGuiPolicyV2_1.canConfirmSafeStop(state));
            controllerEvidence.setEnabled(testMode &&
                FaultGuiPolicyV2_1.canReturnControllerEvidence(
                    state, FaultSupervisorStateV2_1.decision()
                ));
            manualEvidence.setEnabled(testMode &&
                FaultGuiPolicyV2_1.canRecordManualEvidence(state));
            resume.setEnabled(testMode && FaultGuiPolicyV2_1.canResume(state));
        }

        private void updateRecovery(String state) {
            recovery.removeAll();
            int active = recoveryIndex(state);
            String[] en = {"1  Detect", "2  Safe stop", "3  Recover", "4  Verify", "5  Resume"};
            String[] zh = {"1  检测", "2  安全停机", "3  恢复", "4  验证", "5  恢复运行"};
            for (int index = 0; index < en.length; index++) {
                JLabel step = badge(chinese ? zh[index] : en[index],
                    index < active ? GREEN : index == active ? AMBER :
                    new Color(154, 167, 161));
                step.setHorizontalAlignment(SwingConstants.CENTER);
                step.setBorder(BorderFactory.createEmptyBorder(17, 9, 17, 9));
                recovery.add(step);
            }
            recovery.revalidate();
            recovery.repaint();
        }

        private String incidentText(String state) {
            return (chinese ? "监督决策\n" : "SUPERVISOR DECISION\n") +
                value(FaultSupervisorStateV2_1.decision(), "-") + "\n\n" +
                (chinese ? "恢复策略\n" : "RECOVERY POLICY\n") +
                value(FaultSupervisorStateV2_1.policySummary(), "-") + "\n\n" +
                (chinese ? "已验证证据\n" : "VALIDATED EVIDENCE\n") +
                value(FaultSupervisorStateV2_1.latestEvidence(), "-") + "\n\n" +
                (chinese ? "事件与版本\n" : "EVENT AND VERSION\n") +
                value(FaultSupervisorStateV2_1.activeEventId(), "-") +
                "  |  v" + FaultSupervisorStateV2_1.latestStateVersion() +
                "  |  attempt " + FaultSupervisorStateV2_1.activeAttempt() + "\n\n" +
                (chinese ? "运行指标\n" : "SESSION METRICS\n") +
                FaultSupervisorStateV2_1.metricsSnapshot().summary();
        }

        private String historyText() {
            String[] events = FaultSupervisorStateV2_1.historySnapshot();
            if (events.length == 0) {
                return chinese ? "尚无协议事件。" : "No protocol events recorded.";
            }
            StringBuilder text = new StringBuilder();
            for (int index = events.length - 1; index >= 0; index--) {
                text.append(events[index]).append('\n');
            }
            return text.toString();
        }

        private String nextAction(String state) {
            if ("WAITING_SAFE_STOP".equals(state)) {
                return chinese ? "等待 M1 确认安全停机" : "Await M1 safe-stop confirmation";
            }
            if ("WAITING_ACK".equals(state)) {
                return chinese ? "等待控制器确认恢复请求" : "Await controller recovery acknowledgement";
            }
            if ("WAITING_RESULT".equals(state)) {
                return chinese ? "等待控制器返回更新证据" : "Await newer controller result evidence";
            }
            if ("RESOURCE_WAIT".equals(state)) {
                return chinese ? "补充资源后提交控制器证据" : "Replenish resource, then submit controller evidence";
            }
            if ("LOCKED_OUT".equals(state)) {
                return chinese ? "禁止自动重试，需要人工核对" : "Automatic retry prohibited; manual reconciliation required";
            }
            if ("RECOVERY_READY".equals(state)) {
                return chinese ? "恢复已验证，等待 M1 决策" : "Recovery verified; await M1 resume decision";
            }
            return chinese ? "等待控制器故障事件" : "Waiting for a controller fault event";
        }

        private void applyLanguage() {
            language.setText(chinese ? "EN" : "中文");
            title.setText(chinese ? "容错监督系统" : "Fault-Tolerance Supervisor");
            recoveryTitle.setText(chinese ? "基于证据门控的恢复流程" : "Evidence-gated recovery");
            processTitle.setText(chinese ? "瓶体流程与设备状态" : "Bottle journey and machine state");
            details.setTitleAt(0, chinese ? "决策与证据" : "Decision & Evidence");
            details.setTitleAt(1, chinese ? "测试模式" : "Test Mode");
            details.setTitleAt(2, chinese ? "事件历史" : "Event History");
            authority.setText(chinese ? "M1 持有停线与恢复权限" : "M1 owns HOLD / RESUME");
            inject.setText(chinese ? "注入故障" : "Inject fault");
            safeStop.setText(chinese ? "确认安全停机" : "Confirm safe stop");
            controllerEvidence.setText(chinese ? "提交控制器证据" : "Submit controller evidence");
            manualEvidence.setText(chinese ? "记录人工核对" : "Record reconciliation");
            resume.setText(chinese ? "模拟 M1 恢复授权" : "Simulate M1 resume");
            reset.setText(chinese ? "重置" : "Reset");
        }

        private JPanel section(String eyebrow, Component content) {
            return section(eyebrow, null, content);
        }

        private JPanel section(String eyebrow, JLabel heading, Component content) {
            JPanel panel = card(new BorderLayout(0, 11));
            JLabel eyebrowLabel = label(eyebrow, 11, true);
            eyebrowLabel.setForeground(TEAL);
            JPanel headingPanel = new JPanel();
            headingPanel.setOpaque(false);
            headingPanel.setLayout(new BoxLayout(headingPanel, BoxLayout.Y_AXIS));
            headingPanel.add(eyebrowLabel);
            if (heading != null) {
                headingPanel.add(Box.createVerticalStrut(4));
                headingPanel.add(heading);
            }
            panel.add(headingPanel, BorderLayout.NORTH);
            panel.add(content, BorderLayout.CENTER);
            panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(15, 17, 17, 17)
            ));
            return panel;
        }

        private static JPanel card(java.awt.LayoutManager layout) {
            JPanel panel = new JPanel(layout);
            panel.setBackground(Color.WHITE);
            panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(15, 17, 17, 17)
            ));
            return panel;
        }

        private static JLabel label(String text, int size, boolean bold) {
            JLabel label = new JLabel(text);
            label.setFont(new Font(Font.SANS_SERIF,
                bold ? Font.BOLD : Font.PLAIN, size));
            label.setForeground(INK);
            return label;
        }

        private static JLabel badge(String text, Color color) {
            JLabel label = label(text, 12, true);
            label.setOpaque(true);
            label.setForeground(Color.WHITE);
            label.setBackground(color);
            label.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            return label;
        }

        private static JButton button(String text) {
            JButton button = new JButton(text);
            button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            button.setFocusPainted(false);
            return button;
        }

        private void updateResponsiveLayout() {
            boolean compact = getWidth() < 980;
            process.setCompact(compact);
            recovery.setLayout(new GridLayout(compact ? 2 : 1,
                compact ? 3 : 5, 9, 9));
            recovery.setPreferredSize(new Dimension(700, compact ? 104 : 52));
            subtitle.setVisible(getWidth() >= 880);
            recovery.revalidate();
        }

        private static JTextArea textArea() {
            JTextArea area = new JTextArea();
            area.setEditable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            area.setForeground(new Color(48, 62, 56));
            area.setBackground(SURFACE);
            area.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            return area;
        }

        private static Color stateColor(String state) {
            if ("IDLE".equals(state) || "COMPLETED".equals(state)) {
                return GREEN;
            }
            if ("LOCKED_OUT".equals(state) || "RECOVERY_FAILED".equals(state)) {
                return RED;
            }
            return AMBER;
        }

        private static int recoveryIndex(String state) {
            if ("IDLE".equals(state)) return 0;
            if ("WAITING_SAFE_STOP".equals(state)) return 1;
            if ("WAITING_ACK".equals(state) || "RESOURCE_WAIT".equals(state) ||
                "LOCKED_OUT".equals(state)) return 2;
            if ("WAITING_RESULT".equals(state)) return 3;
            if ("RECOVERY_READY".equals(state)) return 4;
            if ("COMPLETED".equals(state)) return 5;
            return 0;
        }

        private static String value(String value, String fallback) {
            return value == null || value.length() == 0 ? fallback : value;
        }

        private static String human(String value) {
            if (value == null) return "";
            String lower = value.toLowerCase().replace('_', ' ');
            return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }

        private final class ProcessPanel extends JPanel {
            private final String[] stations = {
                "Bottle loader", "Bottle transfer", "Rotary table", "Filler A",
                "Filler B", "Lid handling", "Capper", "Label / unload"
            };
            private String subsystem = "";
            private String state = "IDLE";
            private String fault = "";
            private int pulse;
            private boolean compact;

            ProcessPanel() {
                setPreferredSize(new Dimension(1000, 175));
                setBackground(Color.WHITE);
                new Timer(80, event -> {
                    pulse = (pulse + 1) % 30;
                    repaint();
                }).start();
            }

            void setState(String subsystem, String state, String fault) {
                this.subsystem = subsystem == null ? "" : subsystem;
                this.state = state == null ? "IDLE" : state;
                this.fault = fault == null ? "" : fault;
                repaint();
            }

            void setCompact(boolean compact) {
                if (this.compact == compact) {
                    return;
                }
                this.compact = compact;
                setPreferredSize(new Dimension(700, compact ? 285 : 175));
                revalidate();
                repaint();
            }

            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth();
                int margin = 22;
                int gap = compact ? 12 : 9;
                int columns = compact ? 4 : 8;
                int cardWidth = Math.max(70,
                    (width - margin * 2 - gap * (columns - 1)) / columns);
                int cardHeight = compact ? 94 : 112;
                int active = activeStation();

                for (int index = 0; index < stations.length; index++) {
                    int row = index / columns;
                    int column = index % columns;
                    int x = margin + column * (cardWidth + gap);
                    int y = 24 + row * (cardHeight + 18);
                    boolean selected = index == active;
                    g.setColor(selected ? tint(stateColor(state)) : SURFACE);
                    g.fillRoundRect(x, y, cardWidth, cardHeight, 8, 8);
                    g.setColor(selected ? stateColor(state) : BORDER);
                    g.setStroke(new BasicStroke(selected ? 2.2f : 1.2f));
                    g.drawRoundRect(x, y, cardWidth, cardHeight, 8, 8);
                    g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
                    g.setColor(new Color(124, 126, 131));
                    g.drawString(String.format("%02d", index + 1), x + 9, y + 17);
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                    g.setColor(selected ? stateColor(state) : INK);
                    drawCentered(g, twoLine(stationName(index)), x, y + 38, cardWidth);
                    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
                    g.setColor(new Color(100, 116, 108));
                    String status = active < 0 ? (chinese ? "正常" : "READY") :
                        selected ? human(fault) :
                        (index < active ? "HELD SAFE" : "WAITING");
                    drawCentered(g, twoLine(status), x, y + 70, cardWidth);
                    if (selected) {
                        int radius = 4 + pulse / 10;
                        g.setColor(stateColor(state));
                        g.fillOval(x + cardWidth - 18 - radius, y + 14 - radius,
                            radius * 2, radius * 2);
                    }
                }
                g.dispose();
            }

            private String stationName(int index) {
                if (!chinese) {
                    return stations[index];
                }
                String[] names = {
                    "上瓶机", "瓶体输送", "旋转工作台", "灌装机 A",
                    "灌装机 B", "瓶盖装载", "旋盖机", "贴标与卸载"
                };
                return names[index];
            }

            private int activeStation() {
                if ("ROTARY".equals(subsystem)) return 2;
                if ("LID".equals(subsystem)) return 5;
                if ("TRANSFER".equals(subsystem)) return 1;
                return -1;
            }

            private String twoLine(String value) {
                if (value.length() < 15 || value.indexOf(' ') < 0) return value;
                int split = value.lastIndexOf(' ', value.length() / 2 + 2);
                return split > 0 ? value.substring(0, split) + "\n" +
                    value.substring(split + 1) : value;
            }

            private void drawCentered(Graphics2D g, String text, int x, int y, int width) {
                String[] lines = text.split("\\n");
                for (int index = 0; index < lines.length; index++) {
                    int textWidth = g.getFontMetrics().stringWidth(lines[index]);
                    g.drawString(lines[index], x + (width - textWidth) / 2,
                        y + index * 15);
                }
            }

            private Color tint(Color color) {
                return new Color(
                    (color.getRed() + 255 * 5) / 6,
                    (color.getGreen() + 255 * 5) / 6,
                    (color.getBlue() + 255 * 5) / 6
                );
            }
        }
    }
}
