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
import java.text.SimpleDateFormat;
import java.util.Date;
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
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;

/** Native Swing monitoring display for the M3 fault-tolerance supervisor. */
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
        private static final Color BACKGROUND = new Color(241, 244, 243);
        private static final Color BORDER = new Color(205, 212, 209);
        private static final Color TEXT = new Color(30, 37, 35);
        private static final Color MUTED = new Color(91, 103, 99);
        private static final Color BLUE = new Color(25, 104, 163);
        private static final Color GREEN = new Color(24, 124, 80);
        private static final Color AMBER = new Color(178, 105, 8);
        private static final Color RED = new Color(183, 52, 48);

        private final JLabel title = heading("M3 Fault-Tolerance Monitor", 21);
        private final ActivityIndicator activity = new ActivityIndicator();
        private final JLabel health = statusLabel("STARTING", BLUE);
        private final JLabel clock = valueLabel("--:--:--");
        private final JToggleButton mode = new JToggleButton("LIVE");
        private final JLabel workingStatus = statusLabel("STARTING", BLUE);
        private final JLabel backendState = valueLabel("-");
        private final JLabel currentTask = valueLabel("Starting monitoring");
        private final JLabel currentStage = valueLabel("-");
        private final JLabel counts = valueLabel("Warnings 0   Errors 0   Faults 0");
        private final JTextArea alert = readOnlyArea(false);
        private final JTextArea feedback = readOnlyArea(false);
        private final JProgressBar progress = new JProgressBar(0, 5);
        private final DefaultTableModel componentModel = new DefaultTableModel(
            new Object[] {"Component", "Owner", "State", "Health / link",
                "Last seen", "Last healthy", "Detail"}, 0
        ) {
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        private final JTable componentTable = new JTable(componentModel);
        private final FaultMonitoringViewV2_1 monitoringView =
            new FaultMonitoringViewV2_1();
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
        private final JButton controllerEvidence = new JButton("Submit controller evidence");
        private final JButton manualEvidence = new JButton("Record reconciliation");
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
            super("M3 Fault-Tolerance Monitor");
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setMinimumSize(new Dimension(820, 580));
            setSize(new Dimension(1120, 720));
            setLocationRelativeTo(null);
            mode.setFocusPainted(false);
            mode.setOpaque(true);
            mode.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
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
                BorderFactory.createEmptyBorder(9, 14, 9, 14)
            ));
            JPanel identity = new JPanel(new FlowLayout(FlowLayout.LEFT, 9, 0));
            identity.setOpaque(false);
            activity.setPreferredSize(new Dimension(30, 30));
            identity.add(activity);
            identity.add(title);
            identity.add(health);
            header.add(identity, BorderLayout.WEST);
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
            controls.setOpaque(false);
            controls.add(clock);
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
            panel.add(field("Working status", workingStatus), c);
            c.gridx = 1;
            panel.add(field("Backend state", backendState), c);
            c.gridx = 2;
            c.weightx = 1;
            panel.add(field("Current task", currentTask), c);
            c.gridx = 3;
            c.weightx = 0;
            panel.add(field("Current stage", currentStage), c);
            c.gridx = 0;
            c.gridy = 1;
            c.gridwidth = 4;
            c.weightx = 1;
            progress.setStringPainted(true);
            progress.setForeground(BLUE);
            panel.add(progress, c);
            c.gridy = 2;
            c.gridwidth = 3;
            panel.add(field("Warning / fault / next action", scroll(alert)), c);
            c.gridx = 3;
            c.gridwidth = 1;
            c.weightx = 0;
            panel.add(field("Session counts", counts), c);
            c.gridx = 0;
            c.gridy = 3;
            c.gridwidth = 4;
            c.weightx = 1;
            panel.add(field("Last control action", scroll(feedback)), c);
            return panel;
        }

        private JTabbedPane buildTabs() {
            tabs.addTab("Dynamic monitoring", monitoringView);
            tabs.addTab("Fault details", buildDetailsTab());
            tabs.addTab("Event log", scroll(history));
            tabs.addTab("Test controls", buildTestTab());
            return tabs;
        }

        private JPanel buildDetailsTab() {
            JPanel content = new JPanel(new BorderLayout(0, 8));
            content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            componentTable.setFillsViewportHeight(true);
            componentTable.setRowHeight(25);
            componentTable.setAutoCreateRowSorter(false);
            componentTable.getTableHeader().setReorderingAllowed(false);
            componentTable.setDefaultRenderer(Object.class, new ComponentRenderer());
            TableColumnModel columns = componentTable.getColumnModel();
            columns.getColumn(0).setPreferredWidth(155);
            columns.getColumn(1).setPreferredWidth(45);
            columns.getColumn(2).setPreferredWidth(110);
            columns.getColumn(3).setPreferredWidth(155);
            columns.getColumn(4).setPreferredWidth(80);
            columns.getColumn(5).setPreferredWidth(90);
            columns.getColumn(6).setPreferredWidth(260);
            JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                scroll(details),
                new JScrollPane(componentTable)
            );
            split.setResizeWeight(0.48);
            split.setContinuousLayout(true);
            split.setBorder(null);
            split.setDividerLocation(210);
            content.add(split, BorderLayout.CENTER);
            JLabel note = new JLabel(
                "Detailed values remain available here; the dynamic view does not infer external machine state."
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
                "Enable TEST MODE in the header to use controlled fault injection."
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
            mode.addActionListener(event -> {
                FaultGuiActionsV2_1.setTestMode(mode.isSelected());
                actionFailed = false;
                actionFeedback = mode.isSelected() ?
                    t("Test controls enabled", "测试控制已启用") :
                    t("Live monitoring mode enabled", "实时监控模式已启用");
                refresh();
            });
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
            FaultMonitoringStateV2_1.heartbeat(
                FaultMonitoringStateV2_1.GUI_WORKER, true, "Swing refresh worker"
            );
            FaultMonitoringStateV2_1.Snapshot snapshot =
                FaultMonitoringStateV2_1.snapshot();
            String viewState = displayState(snapshot.supervisorState);
            health.setText(t("SYSTEM ", "系统 ") + snapshot.systemHealth);
            health.setBackground(healthColor(snapshot.systemHealth));
            clock.setText(new SimpleDateFormat("HH:mm:ss").format(
                new Date(snapshot.capturedAtMs)
            ));
            workingStatus.setText(viewState);
            workingStatus.setBackground(statusColor(snapshot.supervisorState));
            backendState.setText(snapshot.supervisorState);
            currentTask.setText(actionRunning ? runningAction : currentTask(snapshot));
            currentStage.setText(currentStage(snapshot));
            counts.setText(t("Warnings ", "警告 ") + snapshot.warnings +
                t("   Errors ", "   错误 ") + snapshot.errors +
                t("   Faults ", "   故障 ") + snapshot.faults);
            activity.update(snapshot.systemHealth, viewState);
            monitoringView.updateSnapshot(snapshot, chinese);
            updateProgress(snapshot);
            updateMessages(snapshot);
            updateComponentTable(snapshot);
            details.setText(detailsText(snapshot, viewState));
            history.setText(historyText());
            updateButtons(snapshot);
        }

        private void updateMessages(FaultMonitoringStateV2_1.Snapshot snapshot) {
            boolean error = "FAILED".equals(snapshot.supervisorState) ||
                "LOCKED_OUT".equals(snapshot.supervisorState);
            alert.setForeground(error ? RED : TEXT);
            alert.setText(nextAction(snapshot));
            feedback.setForeground(actionFailed ? RED : GREEN);
            feedback.setText(value(actionFeedback,
                t("No control action in this session", "本次会话尚无控制操作")));
        }

        private void updateProgress(FaultMonitoringStateV2_1.Snapshot snapshot) {
            int step = recoveryStep(snapshot.supervisorState);
            progress.setValue(step);
            String retry = snapshot.maximumAttempts > 0 ?
                t(" | retry ", " | 重试 ") + snapshot.attempt + "/" +
                    snapshot.maximumAttempts : "";
            progress.setString(stepName(step) + "  (" + step + "/5)" + retry);
            progress.setForeground(statusColor(snapshot.supervisorState));
        }

        private void updateComponentTable(FaultMonitoringStateV2_1.Snapshot snapshot) {
            componentModel.setRowCount(0);
            for (FaultMonitoringStateV2_1.ComponentSnapshot component : snapshot.components) {
                componentModel.addRow(new Object[] {
                    component.name, component.owner, component.state,
                    component.heartbeat, formatAge(component.lastSeenAgeMs),
                    formatAge(component.lastHealthyAgeMs),
                    value(component.detail, "-")
                });
            }
        }

        private void updateButtons(FaultMonitoringStateV2_1.Snapshot snapshot) {
            boolean testMode = FaultGuiActionsV2_1.isTestMode();
            mode.setSelected(testMode);
            mode.setText(testMode ? "TEST MODE" : "LIVE");
            mode.setBackground(testMode ? AMBER : GREEN);
            mode.setForeground(Color.WHITE);
            mode.setEnabled(!actionRunning);
            String state = snapshot.supervisorState;
            inject.setEnabled(!actionRunning && testMode && FaultGuiPolicyV2_1.canInject(state));
            safeStop.setEnabled(!actionRunning && testMode && FaultGuiPolicyV2_1.canConfirmSafeStop(state));
            controllerEvidence.setEnabled(!actionRunning && testMode &&
                FaultGuiPolicyV2_1.canReturnControllerEvidence(state, snapshot.decision));
            manualEvidence.setEnabled(!actionRunning && testMode &&
                FaultGuiPolicyV2_1.canRecordManualEvidence(state));
            resume.setEnabled(!actionRunning && testMode && FaultGuiPolicyV2_1.canResume(state));
            reset.setEnabled(!actionRunning);
            language.setEnabled(!actionRunning);
            faults.setEnabled(inject.isEnabled());
        }

        private String detailsText(
            FaultMonitoringStateV2_1.Snapshot snapshot,
            String viewState
        ) {
            long inState = Math.max(0L, snapshot.capturedAtMs - snapshot.stateEnteredAtMs);
            return line("System health", snapshot.systemHealth) +
                line("Monitoring visibility", snapshot.visibility) +
                line("Working status", viewState) +
                line("Backend state", snapshot.supervisorState) +
                line("Time in state", formatDuration(inState)) +
                line("Decision / error", snapshot.decision) +
                line("Fault component", snapshot.subsystem) +
                line("Fault type", snapshot.faultCode) +
                line("Severity", snapshot.severity) +
                line("Bottle ID", snapshot.bottleId) +
                line("Event ID", snapshot.eventId) +
                line("Source epoch", snapshot.sourceEpoch) +
                line("Event state version", String.valueOf(snapshot.eventStateVersion)) +
                line("Latest state version", String.valueOf(snapshot.latestStateVersion)) +
                line("Recovery attempt", snapshot.attempt + " / " + snapshot.maximumAttempts) +
                line("Fault component last healthy", activeLastHealthy(snapshot)) +
                line("Recovery policy", snapshot.policy) +
                line("Required safe evidence", snapshot.requiredSafeEvidence) +
                line("Required service evidence", snapshot.requiredServiceEvidence) +
                line("Latest validated evidence", snapshot.latestEvidence) +
                line("Local controller state", snapshot.localState) +
                line("Session metrics", snapshot.metrics.summary());
        }

        private String activeLastHealthy(FaultMonitoringStateV2_1.Snapshot snapshot) {
            String componentName = "ROTARY".equals(snapshot.subsystem) ?
                FaultMonitoringStateV2_1.ROTARY_CONTROLLER :
                "LID".equals(snapshot.subsystem) ?
                    FaultMonitoringStateV2_1.LID_CONTROLLER :
                    "TRANSFER".equals(snapshot.subsystem) ?
                        FaultMonitoringStateV2_1.M2_LINK : "";
            for (FaultMonitoringStateV2_1.ComponentSnapshot component : snapshot.components) {
                if (component.name.equals(componentName)) {
                    return formatAge(component.lastHealthyAgeMs);
                }
            }
            return "-";
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
            if ("WAITING_RESULT".equals(state)) return t("RECOVERING", "恢复中");
            if ("RECOVERY_READY".equals(state)) return t("VERIFIED / WAITING", "验证完成 / 等待授权");
            if ("LOCKED_OUT".equals(state)) return t("STOPPED / ISOLATED", "已停止 / 已隔离");
            if ("FAILED".equals(state)) return t("FAILED", "失败");
            if ("MANUAL_RECOVERY".equals(state)) return t("RECOVERING", "恢复中");
            return t("WAITING", "等待中");
        }

        private String currentTask(FaultMonitoringStateV2_1.Snapshot snapshot) {
            String state = snapshot.supervisorState;
            if ("WAITING_SAFE_STOP".equals(state)) return t("Isolating fault and awaiting M1 safe stop", "隔离故障并等待 M1 安全停机");
            if ("WAITING_ACK".equals(state)) return t("Sending bounded recovery request", "发送有限次数恢复请求");
            if ("WAITING_RESULT".equals(state)) return t("Controller recovery in progress", "控制器正在恢复");
            if ("RESOURCE_WAIT".equals(state)) return t("Waiting for resource replenishment", "等待资源补充");
            if ("MANUAL_RECOVERY".equals(state) || "LOCKED_OUT".equals(state)) return t("Fault isolated; manual reconciliation required", "故障已隔离，需要人工核对");
            if ("RECOVERY_READY".equals(state)) return t("Evidence verified; waiting for M1", "证据已验证，等待 M1");
            if ("FAILED".equals(state)) return t("Recovery failed", "恢复失败");
            return t("Monitoring M3 runtime and controller events", "监控 M3 运行状态与控制器事件");
        }

        private String currentStage(FaultMonitoringStateV2_1.Snapshot snapshot) {
            if ("-".equals(snapshot.subsystem)) return t("Monitoring", "监控");
            return snapshot.subsystem + " / " + value(snapshot.faultCode, "-");
        }

        private String nextAction(FaultMonitoringStateV2_1.Snapshot snapshot) {
            String state = snapshot.supervisorState;
            String source = "-".equals(snapshot.subsystem) ? "" :
                snapshot.subsystem + " / " + snapshot.faultCode + ": ";
            if ("WAITING_SAFE_STOP".equals(state)) return source + t("fault detected and isolated; waiting for M1 safe-stop confirmation.", "检测并隔离故障，等待 M1 确认安全停机。");
            if ("WAITING_ACK".equals(state)) return source + t("waiting for controller acknowledgement.", "等待控制器确认恢复请求。");
            if ("WAITING_RESULT".equals(state)) return source + t("recovering; waiting for newer controller evidence.", "正在恢复，等待控制器返回更新证据。");
            if ("RESOURCE_WAIT".equals(state)) return source + t("replenish the resource and submit controller evidence.", "补充资源并提交控制器证据。");
            if ("LOCKED_OUT".equals(state)) return source + t("automatic recovery stopped. " + snapshot.decision, "自动恢复已停止。" + snapshot.decision);
            if ("RECOVERY_READY".equals(state)) return source + t("recovery verified; M1 retains the resume decision.", "恢复已验证，恢复运行仍由 M1 决定。");
            if ("FAILED".equals(state)) return source + t("recovery failed: ", "恢复失败：") + snapshot.decision + " | " + snapshot.latestEvidence;
            return t("No active fault. Monitoring live M3 heartbeats and interface traffic.", "当前无活动故障，正在监控 M3 实时心跳与接口通信。");
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
            String[] en = {"Monitoring", "Detect / isolate", "Recovery decision", "Recovering", "Evidence verified", "Resumed"};
            String[] zh = {"监控中", "检测 / 隔离", "恢复决策", "恢复执行中", "证据已验证", "已恢复运行"};
            return chinese ? zh[step] : en[step];
        }

        private Color statusColor(String state) {
            if (actionRunning || "WAITING_RESULT".equals(state)) return BLUE;
            if ("IDLE".equals(state) || "RECOVERY_READY".equals(state)) return GREEN;
            if ("FAILED".equals(state) || "LOCKED_OUT".equals(state)) return RED;
            return AMBER;
        }

        private Color healthColor(String systemHealth) {
            if ("HEALTHY".equals(systemHealth)) return GREEN;
            if ("CRITICAL".equals(systemHealth)) return RED;
            return AMBER;
        }

        private String actionName(String action) {
            if ("inject".equals(action)) return t("Inject fault", "注入故障");
            if ("safe-stop".equals(action)) return t("Confirm safe stop", "确认安全停机");
            if ("controller-evidence".equals(action)) return t("Submit controller evidence", "提交控制器证据");
            if ("manual-evidence".equals(action)) return t("Record reconciliation", "记录人工核对");
            if ("resume".equals(action)) return t("Simulate M1 resume", "模拟 M1 恢复授权");
            return t("Reset", "重置");
        }

        private void applyLanguage() {
            title.setText(t("M3 Fault-Tolerance Monitor", "M3 容错监控系统"));
            language.setText(chinese ? "EN" : "中文");
            reset.setText(t("Reset", "重置"));
            inject.setText(t("Inject fault", "注入故障"));
            safeStop.setText(t("Confirm safe stop", "确认安全停机"));
            controllerEvidence.setText(t("Submit controller evidence", "提交控制器证据"));
            manualEvidence.setText(t("Record reconciliation", "记录人工核对"));
            resume.setText(t("Simulate M1 resume", "模拟 M1 恢复授权"));
            tabs.setTitleAt(0, t("Dynamic monitoring", "动态监控"));
            tabs.setTitleAt(1, t("Fault details", "故障详情"));
            tabs.setTitleAt(2, t("Event log", "事件日志"));
            tabs.setTitleAt(3, t("Test controls", "测试控制"));
        }

        private String t(String english, String chineseText) {
            return chinese ? chineseText : english;
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

        private static JPanel panel(java.awt.LayoutManager layout) {
            JPanel panel = new JPanel(layout);
            panel.setBackground(Color.WHITE);
            panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(9, 11, 9, 11)
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
            scroll.setPreferredSize(new Dimension(200, 43));
            return scroll;
        }

        private static String line(String name, String text) {
            return name + ": " + value(text, "-") + "\n";
        }

        private static String value(String text, String fallback) {
            return text == null || text.trim().length() == 0 ? fallback : text;
        }

        private final class ComponentRenderer extends DefaultTableCellRenderer {
            public Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focus,
                int row, int column
            ) {
                Component component = super.getTableCellRendererComponent(
                    table, value, selected, focus, row, column
                );
                if (column == 2) {
                    setText(animatedState(String.valueOf(value)));
                }
                if (!selected) {
                    component.setBackground(row % 2 == 0 ? Color.WHITE :
                        new Color(247, 249, 248));
                    component.setForeground(TEXT);
                    String stateValue = String.valueOf(table.getValueAt(row, 2));
                    String healthValue = String.valueOf(table.getValueAt(row, 3));
                    if ((column == 2 || column == 3) &&
                        (stateValue.contains("FAULT") || healthValue.contains("UNRESPONSIVE"))) {
                        component.setForeground(RED);
                    }
                    else if ((column == 2 || column == 3) &&
                        (stateValue.contains("RUNNING") || healthValue.contains("RESPONSIVE"))) {
                        component.setForeground(GREEN);
                    }
                    else if ((column == 2 || column == 3) &&
                        (healthValue.contains("LATE") || stateValue.contains("WAITING"))) {
                        component.setForeground(AMBER);
                    }
                }
                return component;
            }

            private String animatedState(String stateValue) {
                if (stateValue.contains("FAULT") ||
                    stateValue.contains("UNRESPONSIVE")) {
                    return "■  " + stateValue;
                }
                if (stateValue.contains("RUNNING") ||
                    stateValue.contains("BUSY") ||
                    stateValue.contains("MOVING")) {
                    boolean pulse = (System.currentTimeMillis() / 400L) % 2L == 0L;
                    return (pulse ? "●  " : "○  ") + stateValue;
                }
                if (stateValue.contains("WAITING") ||
                    stateValue.contains("OBSERVED")) {
                    return "○  " + stateValue;
                }
                return "●  " + stateValue;
            }
        }

        private static final class ActivityIndicator extends JComponent {
            private int phase;
            private String systemHealth = "DEGRADED";
            private String workingState = "STARTING";

            void update(String healthValue, String stateValue) {
                systemHealth = healthValue;
                workingState = stateValue;
                phase = (phase + 18) % 360;
                repaint();
            }

            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
                Color color = "HEALTHY".equals(systemHealth) ? GREEN :
                    "CRITICAL".equals(systemHealth) ? RED : AMBER;
                int size = Math.min(getWidth(), getHeight()) - 8;
                int x = (getWidth() - size) / 2;
                int y = (getHeight() - size) / 2;
                g.setStroke(new BasicStroke(3f));
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 55));
                g.drawOval(x, y, size, size);
                g.setColor(color);
                if (workingState.contains("PROCESSING") ||
                    workingState.contains("RECOVERING") ||
                    workingState.contains("WAITING")) {
                    g.drawArc(x, y, size, size, phase, 105);
                }
                else {
                    int dot = Math.max(7, size / 3);
                    g.fillOval((getWidth() - dot) / 2,
                        (getHeight() - dot) / 2, dot, dot);
                }
                g.dispose();
            }
        }
    }
}
