import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * Handwritten Swing view for the Overall ABS Visualisation Plant.
 *
 * It receives data only through ABSVisualisationPlantCD. It has no Controller
 * connections and contains no machine or Plant control logic.
 */
public final class ABSVisualisation {
    private static final String[] MACHINE_NAMES = {
        "Bottle Loader",
        "Transport",
        "Filler A",
        "Filler B",
        "Lid Loader",
        "Capper"
    };
    private static final int[] STATUSES = new int[MACHINE_NAMES.length];

    private static volatile ABSVisualisation instance;
    private static int requiredBottles = 0;
    private static int completedBottles = 0;

    static {
        Arrays.fill(STATUSES, -1);
    }

    private final JFrame frame;
    private final JLabel[] statusLabels;
    private final JLabel progressLabel;

    private ABSVisualisation() {
        frame = new JFrame("Automated Bottling System");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout(12, 12));

        JLabel title = new JLabel(
            "AUTOMATED BOTTLING SYSTEM",
            SwingConstants.CENTER
        );
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        title.setBorder(BorderFactory.createEmptyBorder(16, 12, 6, 12));
        frame.add(title, BorderLayout.NORTH);

        JPanel machinePanel = new JPanel(new GridBagLayout());
        machinePanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Controller status"),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));
        statusLabels = new JLabel[MACHINE_NAMES.length];
        for (int index = 0; index < MACHINE_NAMES.length; index++) {
            GridBagConstraints nameConstraints = new GridBagConstraints();
            nameConstraints.gridx = 0;
            nameConstraints.gridy = index;
            nameConstraints.anchor = GridBagConstraints.WEST;
            nameConstraints.weightx = 1.0;
            nameConstraints.fill = GridBagConstraints.HORIZONTAL;
            nameConstraints.insets = new Insets(6, 8, 6, 18);
            machinePanel.add(new JLabel(MACHINE_NAMES[index]), nameConstraints);

            JLabel status = new JLabel("WAITING", SwingConstants.CENTER);
            status.setOpaque(true);
            status.setForeground(Color.WHITE);
            status.setBackground(new Color(105, 105, 105));
            status.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            status.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
            status.setPreferredSize(new Dimension(110, 30));
            statusLabels[index] = status;

            GridBagConstraints statusConstraints = new GridBagConstraints();
            statusConstraints.gridx = 1;
            statusConstraints.gridy = index;
            statusConstraints.anchor = GridBagConstraints.EAST;
            statusConstraints.insets = new Insets(6, 8, 6, 8);
            machinePanel.add(status, statusConstraints);
        }
        frame.add(machinePanel, BorderLayout.CENTER);

        progressLabel = new JLabel("Progress: 0 / 0", SwingConstants.CENTER);
        progressLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        progressLabel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Current product batch"),
            BorderFactory.createEmptyBorder(10, 12, 12, 12)
        ));
        frame.add(progressLabel, BorderLayout.SOUTH);

        frame.setPreferredSize(new Dimension(460, 500));
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setResizable(false);
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
                    instance.frame.setVisible(true);
                    System.out.println("ABS Visualisation window opened");
                }
            }
        });
    }

    public static synchronized void updateStatus(String machine, int status) {
        int index = machineIndex(machine);
        if (index < 0 || STATUSES[index] == status) {
            return;
        }

        STATUSES[index] = status;
        System.out.println(
            "ABS Visualisation " + MACHINE_NAMES[index] + "=" +
            statusName(status) + " (" + status + ")"
        );
        refreshStatusOnSwing(index, status);
    }

    public static synchronized void updateRequiredBottles(int required) {
        if (requiredBottles == required) {
            return;
        }
        requiredBottles = required;
        printAndRefreshProgress();
    }

    public static synchronized void updateCompletedBottles(int completed) {
        if (completedBottles == completed) {
            return;
        }
        completedBottles = completed;
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

    private static int machineIndex(String machine) {
        for (int index = 0; index < MACHINE_NAMES.length; index++) {
            if (MACHINE_NAMES[index].equals(machine)) {
                return index;
            }
        }
        return -1;
    }

    private static void refreshStatusOnSwing(
        final int index,
        final int status
    ) {
        final ABSVisualisation ui = instance;
        if (ui == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ui.applyStatus(index, status);
            }
        });
    }

    private static void printAndRefreshProgress() {
        System.out.println(
            "ABS Visualisation Progress=" + completedBottles + "/" +
            requiredBottles
        );
        final ABSVisualisation ui = instance;
        if (ui == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ui.progressLabel.setText(
                    "Progress: " + completedBottles + " / " +
                    requiredBottles
                );
            }
        });
    }

    private void refreshAll() {
        for (int index = 0; index < STATUSES.length; index++) {
            if (STATUSES[index] >= 0) {
                applyStatus(index, STATUSES[index]);
            }
        }
        progressLabel.setText(
            "Progress: " + completedBottles + " / " + requiredBottles
        );
    }

    private void applyStatus(int index, int status) {
        JLabel label = statusLabels[index];
        label.setText(statusName(status));
        label.setBackground(statusColor(status));
    }

    private static Color statusColor(int status) {
        switch (status) {
            case 0:
                return new Color(105, 105, 105);
            case 1:
                return new Color(45, 105, 175);
            case 2:
                return new Color(220, 135, 25);
            case 3:
                return new Color(35, 145, 70);
            case 4:
                return new Color(190, 45, 45);
            default:
                return new Color(90, 90, 90);
        }
    }
}
