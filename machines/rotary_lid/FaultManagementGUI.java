import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;

/** Starts the compact native Swing display for FaultSupervisorCD. */
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
                frame.toFront();
            }
        });
    }

    private static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        }
        catch (Exception ignored) {
            // Swing's default appearance remains usable.
        }
    }

    private static final class DashboardFrame extends JFrame {
        private static final Color BACKGROUND = new Color(242, 243, 245);
        private static final Color BORDER = new Color(209, 212, 216);
        private static final Color TEXT = new Color(35, 38, 42);
        private static final Color MUTED = new Color(91, 96, 103);
        private static final Color BLUE = new Color(28, 102, 173);
        private static final Color GREEN = new Color(35, 126, 75);
        private static final Color AMBER = new Color(174, 103, 9);
        private static final Color RED = new Color(183, 52, 48);

        private static final String[][] STAGES = {
            {"Bottle loader", "M2"},
            {"Bottle transfer", "M2"},
            {"Rotary table", "M3"},
            {"Filler A", "M4"},
            {"Filler B", "M4"},
            {"Lid handling", "M3"},
            {"Capper", "M4"},
            {"Label / unload", "M2"}
        };

        private final JLabel title = heading("Fault-Tolerance Supervisor", 22);
        private final JLabel mode = statusLabel("LIVE", GREEN);
        private final JLabel status = statusLabel("STARTING", BLUE);
        private final JLabel rawState = valueLabel("-");
        private final JLabel currentTask = valueLabel("Starting supervisor display");
        private final JTextArea nextAction = readOnlyArea(false);
        private final JTextArea feedback = readOnlyArea(false);
        private final JProgressBar progress = new JProgressBar(0, 5);
        private final DefaultTableModel processModel = new DefaultTableModel(
            new Object[] {"Stage", "Owner", "Supervisor status", "Detail"}, 0
        ) {
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        private final JTable processTable = new JTable(processModel);
        private final JTextArea details = readOnlyArea(true);
        private final JTextArea history = readOnlyArea(true);
        private final JTabbedPane tabs = new JTabbedPane();
        private final JComboBox<String> faults = new JComboBox<String>(new String[] {
            "ALIGNMENT_TIMEOUT", "MOTOR_STALL", "POSITION_SENSOR_FAILURE",
            "MAGAZINE_EMPTY", "PICK_TIMEOUT", "PLACEMENT_TIMEOUT",
            "LID_SENSOR_FAULT", "ARRIVAL_TIMEOUT", "DEPARTURE_TIMEOUT",
            "PHOTO_EYE_FAILURE", "POSITION_CONFLICT"
        });
        private final JButton inject = new JButton("Inject fault");
        private final JButton safeStop = new JButton("Confirm safe stop");
        private final JButton controllerEvidence =
            new JButton("Submit controller evidence");
        private final JButton manualEvidence =
            new JButton("Record reconciliation");
        private final JButton resume = new JButton("Simulate M1 resume");
        private final JButton reset = new JButton("Reset");
        private final JButton language = new JButton("中文");
        private final Timer refreshTimer;

        private boolean chinese;
        private boolean actionRunning;
        private String runningAction = "";
        private String actionFeedback = "";
        private boolean actionFailed;

        DashboardFrame() {
            super("M3 Fault-Tolerance Supervisor");
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setMinimumSize(new Dimension(760, 560));
            setSize(new Dimension(1040, 720));
            setLocationRelativeTo(null);
            setContentPane(buildContent());
            wireActions();
            refreshTimer = new Timer(250, event -> refresh());
            refreshTimer.start();
            refresh();
        }

        private JPanel buildContent() {
            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(BACKGROUND);
            root.add(buildHeader(), BorderLayout.NORTH);

            JPanel body = new JPanel(new BorderLayout(0, 10));
            body.setOpaque(false);
            body.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
            body.add(buildLiveStatus(), BorderLayout.NORTH);
            body.add(buildTabs(), BorderLayout.CENTER);
            root.add(body, BorderLayout.CENTER);
            return root;
        }

        private JPanel buildHeader() {
            JPanel header = new JPanel(new BorderLayout(12, 0));
            header.setBackground(Color.WHITE);
            header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)
            ));
            header.add(title, BorderLayout.WEST);

            JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
            controls.setOpaque(false);
            controls.add(mode);
            controls.add(language);
            controls.add(reset);
            header.add(controls, BorderLayout.EAST);
            return header;
        }

        private JPanel buildLiveStatus() {
            JPanel panel = panel(new GridBagLayout());
            GridBagConstraints c = constraints();
            c.gridx = 0;
            c.gridy = 0;
            c.weightx = 0;
            panel.add(field("Working status", status), c);
            c.gridx = 1;
            panel.add(field("Backend state", rawState), c);
            c.gridx = 2;
            c.weightx = 1;
            panel.add(field("Current task / stage", currentTask), c);

            c.gridx = 0;
            c.gridy = 1;
            c.gridwidth = 3;
            c.weightx = 1;
            progress.setStringPainted(true);
            progress.setForeground(BLUE);
            panel.add(progress, c);
            c.gridy = 2;
            panel.add(field("Next action / error", scroll(nextAction)), c);
            c.gridy = 3;
            panel.add(field("Last GUI action", scroll(feedback)), c);
            return panel;
        }

        private JTabbedPane buildTabs() {
            tabs.addTab("System", buildSystemTab());
            tabs.addTab("Details", scroll(details));
            tabs.addTab("Event log", scroll(history));
            tabs.addTab("Test controls", buildTestTab());
            return tabs;
        }

        private JPanel buildSystemTab() {
            JPanel content = new JPanel(new BorderLayout(0, 8));
            content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            processTable.setFillsViewportHeight(true);
            processTable.setRowHeight(25);
            processTable.setAutoCreateRowSorter(false);
            processTable.getTableHeader().setReorderingAllowed(false);
            TableColumnModel columns = processTable.getColumnModel();
            columns.getColumn(0).setPreferredWidth(170);
            columns.getColumn(1).setPreferredWidth(55);
            columns.getColumn(2).setPreferredWidth(160);
            columns.getColumn(3).setPreferredWidth(430);
            content.add(new JScrollPane(processTable), BorderLayout.CENTER);

            JLabel note = new JLabel(
                "This table reports FaultSupervisorCD knowledge only; it does not infer machine state."
            );
            note.setForeground(MUTED);
            note.setFont(note.getFont().deriveFont(11f));
            content.add(note, BorderLayout.SOUTH);
            return content;
        }

        private JPanel buildTestTab() {
            JPanel content = new JPanel(new BorderLayout(0, 10));
            content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            JPanel selection = new JPanel(new BorderLayout(8, 0));
            selection.add(faults, BorderLayout.CENTER);
            selection.add(inject, BorderLayout.EAST);
            content.add(selection, BorderLayout.NORTH);

            JPanel actions = new JPanel(new GridLayout(2, 2, 8, 8));
            actions.add(safeStop);
            actions.add(controllerEvidence);
            actions.add(manualEvidence);
            actions.add(resume);
            content.add(actions, BorderLayout.CENTER);
            JLabel note = new JLabel(
                "Test controls use the production policy model and are disabled outside -Dm3.testMode=true."
            );
            note.setForeground(MUTED);
            content.add(note, BorderLayout.SOUTH);
            return content;
        }

        private void wireActions() {
            inject.addActionListener(event -> runAction("inject"));
            safeStop.addActionListener(event -> runAction("safe-stop"));
            controllerEvidence.addActionListener(event -> runAction("controller-evidence"));
            manualEvidence.addActionListener(event -> runAction("manual-evidence"));
            resume.addActionListener(event -> runAction("resume"));
            reset.addActionListener(event -> runAction("reset"));
            language.addActionListener(event -> {
                chinese = !chinese;
                applyLanguage();
                refresh();
            });
        }

        private void runAction(final String action) {
            if (actionRunning) {
                return;
            }
            actionRunning = true;
            actionFailed = false;
            runningAction = actionName(action);
            actionFeedback = t("Running: ", "正在执行：") + runningAction;
            refresh();

            new SwingWorker<Boolean, Void>() {
                private String error;

                protected Boolean doInBackground() {
                    try {
                        return Boolean.valueOf(FaultGuiActionsV2_1.perform(
                            action, String.valueOf(faults.getSelectedItem())
                        ));
                    }
                    catch (RuntimeException exception) {
                        error = exception.getMessage();
                        return Boolean.FALSE;
                    }
                }

                protected void done() {
                    boolean accepted = false;
                    try {
                        accepted = get().booleanValue();
                    }
                    catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        error = exception.getMessage();
                    }
                    catch (ExecutionException exception) {
                        error = exception.getCause() == null ?
                            exception.getMessage() : exception.getCause().getMessage();
                    }
                    actionRunning = false;
                    actionFailed = !accepted;
                    actionFeedback = accepted ?
                        t("Completed: ", "已完成：") + runningAction :
                        value(error, t("Rejected in the current state", "当前状态拒绝该操作"));
                    runningAction = "";
                    refresh();
                }
            }.execute();
        }

        private void refresh() {
            String state = FaultSupervisorStateV2_1.stateName();
            String viewState = displayState(state);
            status.setText(viewState);
            status.setBackground(statusColor(state));
            rawState.setText(state);
            currentTask.setText(actionRunning ? runningAction : currentTask(state));
            updateProgress(state);
            updateMessages(state);
            updateProcessTable(state);
            details.setText(detailsText(state, viewState));
            history.setText(historyText());
            updateButtons(state);
        }

        private void updateMessages(String state) {
            boolean error = "FAILED".equals(state) || "LOCKED_OUT".equals(state);
            nextAction.setForeground(error ? RED : TEXT);
            nextAction.setText(nextAction(state));
            feedback.setForeground(actionFailed ? RED : GREEN);
            feedback.setText(value(actionFeedback, t("No GUI action yet", "尚未执行界面操作")));
        }

        private void updateProgress(String state) {
            int step = recoveryStep(state);
            progress.setValue(step);
            progress.setString(stepName(step) + "  (" + step + "/5)");
        }

        private void updateProcessTable(String state) {
            int active = activeStage(FaultSupervisorStateV2_1.activeSubsystem());
            processModel.setRowCount(0);
            for (int index = 0; index < STAGES.length; index++) {
                boolean selected = index == active;
                processModel.addRow(new Object[] {
                    stageName(index), STAGES[index][1],
                    selected ? displayState(state) :
                        t("No active event", "无活动事件"),
                    selected ? activeDetail() : "-"
                });
            }
        }

        private String activeDetail() {
            return value(FaultSupervisorStateV2_1.activeFaultCode(), "-") +
                " | " + value(FaultSupervisorStateV2_1.decision(), "-");
        }

        private void updateButtons(String state) {
            boolean testMode = Boolean.getBoolean("m3.testMode");
            mode.setText(testMode ? "TEST MODE" : "LIVE");
            mode.setBackground(testMode ? AMBER : GREEN);
            inject.setEnabled(!actionRunning && testMode && FaultGuiPolicyV2_1.canInject(state));
            safeStop.setEnabled(!actionRunning && testMode &&
                FaultGuiPolicyV2_1.canConfirmSafeStop(state));
            controllerEvidence.setEnabled(!actionRunning && testMode &&
                FaultGuiPolicyV2_1.canReturnControllerEvidence(
                    state, FaultSupervisorStateV2_1.decision()
                ));
            manualEvidence.setEnabled(!actionRunning && testMode &&
                FaultGuiPolicyV2_1.canRecordManualEvidence(state));
            resume.setEnabled(!actionRunning && testMode && FaultGuiPolicyV2_1.canResume(state));
            reset.setEnabled(!actionRunning);
            language.setEnabled(!actionRunning);
            faults.setEnabled(inject.isEnabled());
        }

        private String detailsText(String state, String viewState) {
            return line("Working status", viewState) +
                line("Backend state", state) +
                line("Decision", FaultSupervisorStateV2_1.decision()) +
                line("Subsystem", FaultSupervisorStateV2_1.activeSubsystem()) +
                line("Fault code", FaultSupervisorStateV2_1.activeFaultCode()) +
                line("Severity", FaultSupervisorStateV2_1.activeSeverity()) +
                line("Bottle ID", FaultSupervisorStateV2_1.activeBottleId()) +
                line("Event ID", FaultSupervisorStateV2_1.activeEventId()) +
                line("Source epoch", FaultSupervisorStateV2_1.activeEpoch()) +
                line("Event state version",
                    String.valueOf(FaultSupervisorStateV2_1.activeStateVersion())) +
                line("Latest state version",
                    String.valueOf(FaultSupervisorStateV2_1.latestStateVersion())) +
                line("Recovery attempt",
                    String.valueOf(FaultSupervisorStateV2_1.activeAttempt())) +
                line("Recovery policy", FaultSupervisorStateV2_1.policySummary()) +
                line("Required safe evidence", FaultSupervisorStateV2_1.requiredSafeEvidence()) +
                line("Required service evidence",
                    FaultSupervisorStateV2_1.requiredServiceEvidence()) +
                line("Latest validated evidence", FaultSupervisorStateV2_1.latestEvidence()) +
                line("Local controller state", FaultSupervisorStateV2_1.localSummary()) +
                line("Session metrics", FaultSupervisorStateV2_1.metricsSnapshot().summary());
        }

        private String historyText() {
            String[] events = FaultSupervisorStateV2_1.historySnapshot();
            if (events.length == 0) {
                return t("No protocol events recorded.", "尚无协议事件。");
            }
            StringBuilder text = new StringBuilder();
            for (int index = events.length - 1; index >= 0; index--) {
                text.append(events[index]).append('\n');
            }
            return text.toString();
        }

        private String displayState(String state) {
            if (actionRunning) return t("PROCESSING", "处理中");
            if ("IDLE".equals(state)) return t("IDLE", "空闲");
            if ("WAITING_RESULT".equals(state) || "MANUAL_RECOVERY".equals(state)) {
                return t("PROCESSING", "处理中");
            }
            if ("RECOVERY_READY".equals(state)) {
                return t("COMPLETED / WAITING", "恢复完成 / 等待授权");
            }
            if ("LOCKED_OUT".equals(state)) return t("STOPPED", "已停止");
            if ("FAILED".equals(state)) return t("FAILED", "失败");
            return t("WAITING", "等待中");
        }

        private String currentTask(String state) {
            if ("WAITING_SAFE_STOP".equals(state)) return t("Safe stop", "安全停机");
            if ("WAITING_ACK".equals(state)) return t("Recovery request", "恢复请求");
            if ("WAITING_RESULT".equals(state)) return t("Controller recovery", "控制器恢复");
            if ("RESOURCE_WAIT".equals(state)) return t("Resource replenishment", "资源补充");
            if ("MANUAL_RECOVERY".equals(state) || "LOCKED_OUT".equals(state)) {
                return t("Manual reconciliation", "人工核对");
            }
            if ("RECOVERY_READY".equals(state)) return t("M1 resume decision", "M1 恢复授权");
            if ("FAILED".equals(state)) return t("Recovery failed", "恢复失败");
            return t("Monitoring controller events", "监控控制器事件");
        }

        private String nextAction(String state) {
            if ("WAITING_SAFE_STOP".equals(state)) {
                return t("Waiting for M1 safe-stop confirmation.", "等待 M1 确认安全停机。");
            }
            if ("WAITING_ACK".equals(state)) {
                return t("Waiting for the controller to acknowledge the recovery request.",
                    "等待控制器确认恢复请求。");
            }
            if ("WAITING_RESULT".equals(state)) {
                return t("Recovery is running; waiting for newer controller evidence.",
                    "恢复正在执行，等待控制器返回更新证据。");
            }
            if ("RESOURCE_WAIT".equals(state)) {
                return t("Replenish the resource and submit controller evidence.",
                    "补充资源后提交控制器证据。");
            }
            if ("LOCKED_OUT".equals(state)) {
                return t("Stopped: automatic retry is prohibited. Record reconciliation, " +
                    "then submit controller evidence. Decision=" +
                    FaultSupervisorStateV2_1.decision(),
                    "已停止：禁止自动重试。请记录人工核对，然后提交控制器证据。决策=" +
                    FaultSupervisorStateV2_1.decision());
            }
            if ("RECOVERY_READY".equals(state)) {
                return t("Recovery evidence is verified; waiting for M1 to resume.",
                    "恢复证据已验证，等待 M1 授权恢复运行。");
            }
            if ("FAILED".equals(state)) {
                return t("Recovery failed: ", "恢复失败：") +
                    FaultSupervisorStateV2_1.decision() + " | " +
                    FaultSupervisorStateV2_1.latestEvidence();
            }
            return t("Waiting for a validated controller fault event.",
                "等待经过验证的控制器故障事件。");
        }

        private int recoveryStep(String state) {
            if ("WAITING_SAFE_STOP".equals(state)) return 1;
            if ("WAITING_ACK".equals(state) || "RESOURCE_WAIT".equals(state) ||
                "MANUAL_RECOVERY".equals(state) || "LOCKED_OUT".equals(state)) return 2;
            if ("WAITING_RESULT".equals(state)) return 3;
            if ("RECOVERY_READY".equals(state)) return 4;
            if ("FAILED".equals(state)) return 2;
            return 0;
        }

        private String stepName(int step) {
            String[] en = {"Monitoring", "Fault detected", "Safe stop / recovery",
                "Recovery running", "Evidence verified", "Resumed"};
            String[] zh = {"监控中", "检测到故障", "安全停机 / 恢复",
                "恢复执行中", "证据已验证", "已恢复运行"};
            return chinese ? zh[step] : en[step];
        }

        private Color statusColor(String state) {
            if (actionRunning) return BLUE;
            if ("IDLE".equals(state) || "RECOVERY_READY".equals(state)) return GREEN;
            if ("FAILED".equals(state) || "LOCKED_OUT".equals(state)) return RED;
            return AMBER;
        }

        private int activeStage(String subsystem) {
            if ("TRANSFER".equals(subsystem)) return 1;
            if ("ROTARY".equals(subsystem)) return 2;
            if ("LID".equals(subsystem)) return 5;
            return -1;
        }

        private String stageName(int index) {
            if (!chinese) return STAGES[index][0];
            String[] names = {"上瓶机", "瓶体输送", "旋转工作台", "灌装机 A",
                "灌装机 B", "瓶盖装载", "旋盖机", "贴标与卸载"};
            return names[index];
        }

        private String actionName(String action) {
            if ("inject".equals(action)) return t("Inject fault", "注入故障");
            if ("safe-stop".equals(action)) return t("Confirm safe stop", "确认安全停机");
            if ("controller-evidence".equals(action)) {
                return t("Submit controller evidence", "提交控制器证据");
            }
            if ("manual-evidence".equals(action)) {
                return t("Record reconciliation", "记录人工核对");
            }
            if ("resume".equals(action)) return t("Simulate M1 resume", "模拟 M1 恢复授权");
            return t("Reset", "重置");
        }

        private void applyLanguage() {
            title.setText(t("Fault-Tolerance Supervisor", "容错监督系统"));
            language.setText(chinese ? "EN" : "中文");
            reset.setText(t("Reset", "重置"));
            inject.setText(t("Inject fault", "注入故障"));
            safeStop.setText(t("Confirm safe stop", "确认安全停机"));
            controllerEvidence.setText(t("Submit controller evidence", "提交控制器证据"));
            manualEvidence.setText(t("Record reconciliation", "记录人工核对"));
            resume.setText(t("Simulate M1 resume", "模拟 M1 恢复授权"));
            tabs.setTitleAt(0, t("System", "系统"));
            tabs.setTitleAt(1, t("Details", "详情"));
            tabs.setTitleAt(2, t("Event log", "事件日志"));
            tabs.setTitleAt(3, t("Test controls", "测试控制"));
        }

        private String t(String english, String chineseText) {
            return chinese ? chineseText : english;
        }

        private static JPanel panel(java.awt.LayoutManager layout) {
            JPanel panel = new JPanel(layout);
            panel.setBackground(Color.WHITE);
            panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)
            ));
            return panel;
        }

        private static JPanel field(String label, JComponent value) {
            JPanel field = new JPanel();
            field.setOpaque(false);
            field.setLayout(new BoxLayout(field, BoxLayout.Y_AXIS));
            JLabel name = new JLabel(label);
            name.setForeground(MUTED);
            name.setFont(name.getFont().deriveFont(Font.PLAIN, 11f));
            name.setAlignmentX(Component.LEFT_ALIGNMENT);
            value.setAlignmentX(Component.LEFT_ALIGNMENT);
            field.add(name);
            field.add(Box.createVerticalStrut(3));
            field.add(value);
            return field;
        }

        private static GridBagConstraints constraints() {
            GridBagConstraints c = new GridBagConstraints();
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(3, 4, 5, 12);
            return c;
        }

        private static JLabel heading(String text, int size) {
            JLabel label = new JLabel(text);
            label.setForeground(TEXT);
            label.setFont(label.getFont().deriveFont(Font.BOLD, (float) size));
            return label;
        }

        private static JLabel valueLabel(String text) {
            JLabel label = new JLabel(text);
            label.setForeground(TEXT);
            label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
            return label;
        }

        private static JLabel statusLabel(String text, Color color) {
            JLabel label = valueLabel(text);
            label.setOpaque(true);
            label.setForeground(Color.WHITE);
            label.setBackground(color);
            label.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
            return label;
        }

        private static JTextArea readOnlyArea(boolean monospaced) {
            JTextArea area = new JTextArea();
            area.setEditable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setForeground(TEXT);
            area.setBackground(Color.WHITE);
            area.setFont(new Font(monospaced ? Font.MONOSPACED : Font.SANS_SERIF,
                Font.PLAIN, monospaced ? 12 : 13));
            area.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
            return area;
        }

        private static JScrollPane scroll(JTextArea area) {
            JScrollPane scroll = new JScrollPane(area);
            scroll.setBorder(BorderFactory.createLineBorder(BORDER));
            scroll.setPreferredSize(new Dimension(200, 45));
            return scroll;
        }

        private static String line(String name, String text) {
            return name + ": " + value(text, "-") + "\n";
        }

        private static String value(String text, String fallback) {
            return text == null || text.trim().length() == 0 ? fallback : text;
        }
    }
}
