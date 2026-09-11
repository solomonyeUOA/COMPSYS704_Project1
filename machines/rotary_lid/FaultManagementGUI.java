import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Monitoring and test-mode fault-injection GUI for the M3 IP extension. */
public final class FaultManagementGUI {
    private static final AtomicLong TEST_EVENT_SEQUENCE = new AtomicLong(1);
    private static boolean started;

    private FaultManagementGUI() {
    }

    public static synchronized void start() {
        if (started || java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        started = true;
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                createWindow();
            }
        });
    }

    private static void createWindow() {
        JFrame frame = new JFrame("M3 Fault-Tolerance Supervisor");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(760, 520));

        final JLabel supervisor = valueLabel();
        final JLabel decision = valueLabel();
        final JLabel activeEvent = valueLabel();
        final JLabel rotary = valueLabel();
        final JLabel lid = valueLabel();
        final JLabel localPolicy = valueLabel();
        final JTextArea history = new JTextArea();
        history.setEditable(false);
        history.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        history.setLineWrap(false);

        JPanel summary = new JPanel(new GridLayout(6, 2, 12, 8));
        summary.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        addRow(summary, "Supervisor", supervisor);
        addRow(summary, "Decision", decision);
        addRow(summary, "Active event", activeEvent);
        addRow(summary, "Rotary Table", rotary);
        addRow(summary, "Lid Loader", lid);
        addRow(summary, "Local recovery policy", localPolicy);

        JPanel controls = new JPanel();
        if (Boolean.getBoolean("m3.testMode")) {
            JButton arrival = new JButton("Inject arrival timeout");
            arrival.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent event) {
                    injectTransferFault("ARRIVAL_TIMEOUT");
                }
            });
            JButton departure = new JButton("Inject departure timeout");
            departure.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent event) {
                    injectTransferFault("DEPARTURE_TIMEOUT");
                }
            });
            controls.add(arrival);
            controls.add(departure);
        }
        else {
            controls.add(new JLabel(
                "Monitoring mode (use -Dm3.testMode=true for test injection)"
            ));
        }

        frame.add(summary, BorderLayout.NORTH);
        frame.add(new JScrollPane(history), BorderLayout.CENTER);
        frame.add(controls, BorderLayout.SOUTH);

        Timer refresh = new Timer(250, new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                String state = FaultSupervisorStateV2_1.stateName();
                supervisor.setText(state);
                supervisor.setForeground(stateColour(state));
                decision.setText(FaultSupervisorStateV2_1.decision());
                activeEvent.setText(FaultSupervisorStateV2_1.activeEventId());
                rotary.setText(Member3MachineStateV1.statusName(
                    Member3MachineStateV1.getRotaryStatus()
                ));
                lid.setText(Member3MachineStateV1.statusName(
                    Member3MachineStateV1.getLidStatus()
                ));
                localPolicy.setText(FaultSupervisorStateV2_1.localSummary());
                history.setText(joinLines(
                    FaultSupervisorStateV2_1.historySnapshot()
                ));
                history.setCaretPosition(history.getDocument().getLength());
            }
        });
        refresh.start();

        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    private static JLabel valueLabel() {
        JLabel label = new JLabel("-");
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private static void addRow(JPanel panel, String name, JLabel value) {
        panel.add(new JLabel(name));
        panel.add(value);
    }

    private static Color stateColour(String state) {
        if ("FAILED".equals(state) || "MANUAL_RECOVERY".equals(state)) {
            return new Color(176, 38, 38);
        }
        if ("RECOVERY_READY".equals(state)) {
            return new Color(22, 122, 72);
        }
        return new Color(35, 74, 120);
    }

    private static String joinLines(String[] lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    private static void injectTransferFault(String faultCode) {
        long sequence = TEST_EVENT_SEQUENCE.getAndIncrement();
        FaultSupervisorStateV2_1.onTransferFault(
            "V2|GUI-" + sequence + "|GUI-TEST|TRANSFER|" + faultCode +
            "|WARNING|B-GUI|" + sequence
        );
    }
}
