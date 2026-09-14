import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * Handwritten Swing UI for POSCD. This class never opens a network socket;
 * POSCD remains the only sender/receiver of the frozen SystemJ protocol.
 */
public final class POSVisualisation {
    private static final String TEST_ORDER_PROPERTY = "abs.pos.testOrder";
    private static final String LAUNCHER_SHUTDOWN_FILE_PROPERTY =
        "abs.launcher.shutdownFile";
    private static final long TEST_RESET_DELAY_MILLIS = Long.getLong(
        "abs.pos.testResetDelayMillis",
        Long.valueOf(-1L)
    ).longValue();
    private static final long TEST_ORDER_DELAY_MILLIS = Long.getLong(
        "abs.pos.testOrderDelayMillis",
        Long.valueOf(5000)
    ).longValue();
    private static final int TEST_ORDER_COUNT = Math.max(
        1,
        Integer.getInteger("abs.pos.testOrderCount", 1).intValue()
    );
    private static final long TEST_ORDER_INTERVAL_MILLIS = Math.max(
        0L,
        Long.getLong(
            "abs.pos.testOrderIntervalMillis",
            Long.valueOf(1000L)
        ).longValue()
    );
    private static final int ORDER_TRANSMISSION_ATTEMPTS = 3;
    private static final long WATCHDOG_HEARTBEAT_MILLIS = 500L;
    private static final long ORDER_RETRY_MILLIS = Math.max(
        1L,
        Long.getLong(
            "abs.pos.orderRetryMillis",
            Long.valueOf(250L)
        ).longValue()
    );
    private static final long ORDER_SIGNAL_HOLD_MILLIS = Math.max(
        1L,
        Long.getLong(
            "abs.pos.orderSignalHoldMillis",
            Long.valueOf(500L)
        ).longValue()
    );
    private static final long START_MILLIS = System.currentTimeMillis();
    private static final AtomicReference<String> PENDING_ORDER =
        new AtomicReference<String>();
    private static final BoundedStringSignalOfferV1 RESET_OFFER =
        new BoundedStringSignalOfferV1(
            ORDER_TRANSMISSION_ATTEMPTS,
            ORDER_SIGNAL_HOLD_MILLIS,
            ORDER_RETRY_MILLIS
        );

    private static volatile POSVisualisation instance;
    private static int testOrdersReturned = 0;
    private static boolean testResetRequested = false;
    private static long nextTestOrderMillis =
        START_MILLIS + TEST_ORDER_DELAY_MILLIS;
    private static int nextOrderNumber = 1;
    private static String activeOrderId = null;
    private static String pendingTransmissionPayload = null;
    private static int orderTransmissionsRemaining = 0;
    private static int lastOrderTransmissionAttempt = 0;
    private static long nextOrderTransmissionMillis = 0L;
    private static String activeTransmissionPayload = null;
    private static long activeTransmissionUntilMillis = 0L;
    private static boolean transmissionStartedThisPoll = false;
    private static boolean resetInProgress = false;
    private static String activeResetId = null;
    private static int nextResetNumber = 1;
    private static long nextWatchdogHeartbeatMillis;
    private static final Set<String> COMPLETED_ORDER_IDS =
        new HashSet<String>();
    private static final Set<String> COMPLETED_RESET_IDS =
        new HashSet<String>();

    private final JFrame frame;
    private final JTextField orderIdField;
    private final List<ProductInputRow> productRows;
    private final JPanel productListPanel;
    private final JButton addProductButton;
    private final JButton removeProductButton;
    private final JButton submitButton;
    private final JButton resetButton;
    private final JButton exitButton;
    private final JLabel submissionStatus;
    private final JLabel completionStatus;

    private POSVisualisation() {
        frame = new JFrame("Purchase Order System" + SimulationTiming.demoSuffix());
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout(12, 12));

        JLabel title = new JLabel(
            "Purchase Order System",
            SwingConstants.CENTER
        );
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        title.setBorder(BorderFactory.createEmptyBorder(14, 12, 4, 12));
        frame.add(title, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(4, 18, 8, 18));
        GridBagConstraints constraints = baseConstraints();

        addFormLabel(form, constraints, 0, "Order ID");
        orderIdField = new JTextField(nextOrderId(), 18);
        orderIdField.setEditable(false);
        orderIdField.setFocusable(false);
        addFormField(form, constraints, 0, orderIdField);

        productRows = new ArrayList<ProductInputRow>();
        ProductInputRow firstProduct = new ProductInputRow(
            "P1", "2", "25", "75"
        );
        productRows.add(firstProduct);

        productListPanel = new JPanel();
        productListPanel.setLayout(new BoxLayout(
            productListPanel,
            BoxLayout.Y_AXIS
        ));
        JScrollPane productScroll = new JScrollPane(productListPanel);
        productScroll.setPreferredSize(new Dimension(520, 240));
        productScroll.setBorder(BorderFactory.createEmptyBorder());

        addProductButton = new JButton("Add Product");
        addProductButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                addProductRow();
            }
        });
        removeProductButton = new JButton("Remove Last Product");
        removeProductButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                removeLastProductRow();
            }
        });
        JPanel productButtons = new JPanel(
            new FlowLayout(FlowLayout.RIGHT, 8, 2)
        );
        productButtons.add(addProductButton);
        productButtons.add(removeProductButton);
        JPanel productSection = new JPanel(new BorderLayout(4, 4));
        productSection.add(productScroll, BorderLayout.CENTER);
        productSection.add(productButtons, BorderLayout.SOUTH);
        refreshProductPanels();

        constraints.gridx = 0;
        constraints.gridy = 1;
        constraints.gridwidth = 2;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1.0;
        constraints.insets = new Insets(10, 0, 8, 0);
        form.add(productSection, constraints);

        submitButton = new JButton("Submit Order");
        submitButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                queueOrderFromForm();
            }
        });
        resetButton = new JButton("Reset System");
        resetButton.setForeground(new Color(150, 45, 35));
        resetButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                confirmAndRequestSystemReset();
            }
        });
        exitButton = new JButton("Exit Program");
        exitButton.setForeground(new Color(120, 50, 25));
        exitButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                confirmAndExitProgram();
            }
        });
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        actionPanel.add(submitButton);
        actionPanel.add(resetButton);
        actionPanel.add(exitButton);
        constraints.gridy = 2;
        constraints.insets = new Insets(8, 0, 4, 0);
        form.add(actionPanel, constraints);

        submissionStatus = new JLabel("Enter an order and select Submit Order");
        submissionStatus.setHorizontalAlignment(SwingConstants.CENTER);
        constraints.gridy = 3;
        constraints.insets = new Insets(8, 0, 4, 0);
        form.add(submissionStatus, constraints);

        completionStatus = new JLabel(
            "<html><div style='text-align:center'>No completed order yet</div></html>"
        );
        completionStatus.setHorizontalAlignment(SwingConstants.CENTER);
        completionStatus.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(190, 190, 190)),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));
        constraints.gridy = 4;
        constraints.insets = new Insets(8, 0, 8, 0);
        form.add(completionStatus, constraints);

        frame.add(form, BorderLayout.CENTER);
        frame.setPreferredSize(new Dimension(600, 635));
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setResizable(false);
    }

    /** Starts Swing asynchronously; headless tests continue with console logs. */
    public static void start() {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("POS Visualisation started in headless test mode");
            return;
        }

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (instance == null) {
                    instance = new POSVisualisation();
                    instance.frame.setVisible(true);
                    System.out.println("POS Visualisation window opened");
                }
            }
        });
    }

    public static synchronized String nextWatchdogHeartbeat() {
        return nextWatchdogHeartbeat(System.currentTimeMillis());
    }

    static synchronized String nextWatchdogHeartbeat(long nowMillis) {
        if (nowMillis < nextWatchdogHeartbeatMillis) {
            return null;
        }
        nextWatchdogHeartbeatMillis = nowMillis + WATCHDOG_HEARTBEAT_MILLIS;
        return "V1|POS|" + nowMillis + "|RUNNING";
    }

    /**
     * Returns one UI order, or a delayed test-only order supplied with
     * -Dabs.pos.testOrder=... . The default five-second delay can be extended
     * with -Dabs.pos.testOrderDelayMillis=... for network integration tests.
     * Normal GUI runs have no automatic order.
     */
    public static synchronized String pollSubmittedOrder() {
        long now = System.currentTimeMillis();
        transmissionStartedThisPoll = false;
        if (resetInProgress) {
            return null;
        }

        // Keep one logical transport copy PRESENT for a bounded wall-clock
        // window. A one-reaction network pulse can otherwise be overwritten
        // by SimpleClient's following ABSENT before the remote Clock Domain
        // samples it.
        if (activeTransmissionPayload != null) {
            if (now < activeTransmissionUntilMillis) {
                return activeTransmissionPayload;
            }
            activeTransmissionPayload = null;
            activeTransmissionUntilMillis = 0L;
            return null;
        }

        if (pendingTransmissionPayload != null) {
            if (now < nextOrderTransmissionMillis) {
                return null;
            }
            return takeNextOrderTransmission(now);
        }

        String pendingOrder = PENDING_ORDER.getAndSet(null);
        if (pendingOrder == null) {
            String testOrder =
                System.getProperty(TEST_ORDER_PROPERTY, "").trim();
            if (testOrder.length() > 0 &&
                testOrdersReturned < TEST_ORDER_COUNT &&
                activeOrderId == null &&
                now >= nextTestOrderMillis) {
                int separator = testOrder.indexOf('|');
                if (separator <= 0) {
                    testOrdersReturned = TEST_ORDER_COUNT;
                    pendingOrder = testOrder;
                }
                else {
                    testOrdersReturned++;
                    pendingOrder =
                        nextOrderId() + testOrder.substring(separator);
                }
            }
        }

        if (pendingOrder == null) {
            return null;
        }

        pendingTransmissionPayload = pendingOrder;
        orderTransmissionsRemaining = ORDER_TRANSMISSION_ATTEMPTS;
        lastOrderTransmissionAttempt = 0;
        nextOrderTransmissionMillis = now;
        return takeNextOrderTransmission(now);
    }

    private static String takeNextOrderTransmission(long now) {
        String payload = pendingTransmissionPayload;
        lastOrderTransmissionAttempt =
            ORDER_TRANSMISSION_ATTEMPTS - orderTransmissionsRemaining + 1;
        orderTransmissionsRemaining--;
        activeTransmissionPayload = payload;
        activeTransmissionUntilMillis = now + ORDER_SIGNAL_HOLD_MILLIS;
        transmissionStartedThisPoll = true;
        if (orderTransmissionsRemaining == 0) {
            pendingTransmissionPayload = null;
            nextOrderTransmissionMillis = 0L;
        }
        else {
            nextOrderTransmissionMillis =
                activeTransmissionUntilMillis + ORDER_RETRY_MILLIS;
        }
        return payload;
    }

    /** True only on the first logical reaction of a transport copy. */
    public static synchronized boolean isOrderTransmissionStart() {
        return transmissionStartedThisPoll;
    }

    public static synchronized void showSubmitted(final String payload) {
        if (resetInProgress) {
            return;
        }
        int separator = payload.indexOf('|');
        String transmittedOrderId =
            separator > 0 ? payload.substring(0, separator) : null;
        if (activeOrderId == null ||
            !activeOrderId.equals(transmittedOrderId)) {
            activeOrderId = transmittedOrderId;
            System.out.println(
                "[POS-LIFECYCLE] submit attempt=" +
                lastOrderTransmissionAttempt + " order=" + activeOrderId +
                " nextOrderNumber=" + nextOrderNumber +
                " completedIds=" + COMPLETED_ORDER_IDS.size()
            );
            updateSubmissionStatus("Order submitted: " + activeOrderId, false);
        }
        else {
            System.out.println(
                "[POS-LIFECYCLE] ORDER retry attempt=" +
                lastOrderTransmissionAttempt + " order=" + activeOrderId
            );
        }
    }

    public static void showValidationError(final String message) {
        updateSubmissionStatus(message, true);
        setSubmitEnabled(true);
    }

    /**
     * Validates and displays ORDER_COMPLETE. Returns a console line, or null
     * for a duplicate transport copy already handled by the POS.
     */
    public static synchronized String handleCompletion(String payload) {
        if (resetInProgress) {
            return "POS ignored ORDER_COMPLETE while system reset is active: " +
                payload;
        }
        String completedOrderId = "";
        String completionState = "";
        String completionSeconds = "";
        int firstSeparator = payload == null ? -1 : payload.indexOf('|');
        int secondSeparator = payload == null ? -1 :
            payload.indexOf('|', firstSeparator + 1);
        if (firstSeparator > 0 && secondSeparator > firstSeparator) {
            completedOrderId = payload.substring(0, firstSeparator);
            completionState = payload.substring(
                firstSeparator + 1,
                secondSeparator
            );
            completionSeconds = payload.substring(secondSeparator + 1);
        }

        boolean validSeconds = false;
        try {
            validSeconds = Integer.parseInt(completionSeconds) >= 0;
        }
        catch (NumberFormatException error) {
            validSeconds = false;
        }

        if (!"COMPLETED".equals(completionState) || !validSeconds) {
            return "POS received invalid ORDER_COMPLETE: " + payload;
        }

        if (COMPLETED_ORDER_IDS.contains(completedOrderId)) {
            return null;
        }

        if (activeOrderId == null ||
            !completedOrderId.equals(activeOrderId)) {
            return "POS ignored ORDER_COMPLETE for non-active order: " +
                payload;
        }

        COMPLETED_ORDER_IDS.add(completedOrderId);
        if (pendingTransmissionPayload != null &&
            pendingTransmissionPayload.startsWith(completedOrderId + "|")) {
            pendingTransmissionPayload = null;
            orderTransmissionsRemaining = 0;
            nextOrderTransmissionMillis = 0L;
        }
        if (activeTransmissionPayload != null &&
            activeTransmissionPayload.startsWith(completedOrderId + "|")) {
            activeTransmissionPayload = null;
            activeTransmissionUntilMillis = 0L;
            transmissionStartedThisPoll = false;
        }
        activeOrderId = null;
        nextOrderNumber++;
        nextTestOrderMillis =
            System.currentTimeMillis() + TEST_ORDER_INTERVAL_MILLIS;
        System.out.println(
            "[POS-LIFECYCLE] complete order=" + completedOrderId +
            " activeOrderId=null nextOrderNumber=" + nextOrderNumber +
            " completedIds=" + COMPLETED_ORDER_IDS.size()
        );
        String preparedOrderId = nextOrderId();
        showCompletion(
            completedOrderId,
            completionState,
            completionSeconds,
            preparedOrderId
        );
        return "POS received completion: orderId=" + completedOrderId +
            ", status=" + completionState +
            ", completionTime=" + completionSeconds + " seconds";
    }

    private void addProductRow() {
        if (productRows.size() >= OrderV2.MAX_PRODUCTS) {
            return;
        }
        productRows.add(new ProductInputRow("P1", "1", "25", "75"));
        refreshProductPanels();
    }

    private void removeLastProductRow() {
        if (productRows.size() <= 1) {
            return;
        }
        productRows.remove(productRows.size() - 1);
        refreshProductPanels();
    }

    private void refreshProductPanels() {
        productListPanel.removeAll();
        for (int index = 0; index < productRows.size(); index++) {
            productListPanel.add(createProductPanel(
                productRows.get(index),
                index + 1
            ));
        }
        addProductButton.setEnabled(
            productRows.size() < OrderV2.MAX_PRODUCTS
        );
        removeProductButton.setEnabled(productRows.size() > 1);
        productListPanel.revalidate();
        productListPanel.repaint();
    }

    private void queueOrderFromForm() {
        synchronized (POSVisualisation.class) {
            if (resetInProgress) {
                showValidationError("System reset is still in progress");
                return;
            }
        }
        final String payload;
        try {
            payload = buildOrderPayload(
                orderIdField.getText().trim(),
                productRows
            );
        }
        catch (IllegalArgumentException error) {
            showValidationError(error.getMessage());
            return;
        }
        if (!PENDING_ORDER.compareAndSet(null, payload)) {
            showValidationError("An order is already waiting to be submitted");
            return;
        }

        submitButton.setEnabled(false);
        submissionStatus.setForeground(new Color(35, 90, 155));
        submissionStatus.setText("Submitting order...");
    }

    /** Requests coordinated launcher shutdown; does not leave peer CDs running. */
    private void confirmAndExitProgram() {
        int choice = JOptionPane.showConfirmDialog(
            frame,
            "Exit Program stops POS, visualization, M2, M3, M4 and Coordinator.\n" +
                "Any active order will be stopped.",
            "Exit complete simulation?",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        String shutdownFile = System.getProperty(
            LAUNCHER_SHUTDOWN_FILE_PROPERTY, ""
        ).trim();
        if (shutdownFile.length() == 0) {
            JOptionPane.showMessageDialog(
                frame,
                "This POS was not started by tools/project.py.\n" +
                    "Stop the manually started runtimes from their launcher.",
                "Launcher unavailable",
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }
        try {
            requestLauncherShutdown(shutdownFile);
            submitButton.setEnabled(false);
            resetButton.setEnabled(false);
            exitButton.setEnabled(false);
            submissionStatus.setText("Stopping all project runtimes...");
            frame.dispose();
        } catch (IOException error) {
            JOptionPane.showMessageDialog(
                frame,
                "Could not request launcher shutdown: " + error.getMessage(),
                "Exit Program failed",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    /** Writes the per-run request consumed by the Python process launcher. */
    static void requestLauncherShutdown(String shutdownFile) throws IOException {
        Path request = Paths.get(shutdownFile);
        Files.write(request, "POS Exit Program\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void showCompletion(
        final String orderId,
        final String state,
        final String seconds,
        final String preparedOrderId
    ) {
        final POSVisualisation ui = instance;
        if (ui == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ui.orderIdField.setText(preparedOrderId);
                ui.completionStatus.setText(
                    "<html><div style='text-align:center'>" +
                    "<b>Previous completed order</b><br>" +
                    "Order ID: " + orderId + "<br>" +
                    "Status: " + state + "<br>" +
                    "Completion Time: " + seconds + " seconds" +
                    "</div></html>"
                );
                ui.completionStatus.setForeground(new Color(20, 120, 55));
                ui.submissionStatus.setForeground(new Color(20, 120, 55));
                ui.submissionStatus.setText(
                    "Order completed - ready for " + preparedOrderId
                );
                ui.submitButton.setEnabled(true);
            }
        });
    }

    private static String nextOrderId() {
        return String.format("PO%04d", nextOrderNumber);
    }

    private void confirmAndRequestSystemReset() {
        Object[] options = {"Cancel", "Reset System"};
        int choice = JOptionPane.showOptionDialog(
            frame,
            "Reset the entire ABS system?\n" +
                "The active order will be aborted and all runtime state " +
                "will return to its safe initial state.",
            "Confirm System Reset",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[0]
        );
        if (choice == 1) {
            requestSystemReset();
        }
    }

    private static synchronized void requestSystemReset() {
        beginSystemReset(
            String.format("RST%04d", nextResetNumber++),
            System.currentTimeMillis()
        );
    }

    static synchronized boolean beginSystemReset(
        String resetId,
        long nowMillis
    ) {
        if (resetInProgress || resetId == null ||
            !resetId.matches("RST[0-9]{4,}")) {
            return false;
        }

        resetInProgress = true;
        activeResetId = resetId;
        PENDING_ORDER.set(null);
        pendingTransmissionPayload = null;
        orderTransmissionsRemaining = 0;
        nextOrderTransmissionMillis = 0L;
        activeTransmissionPayload = null;
        activeTransmissionUntilMillis = 0L;
        transmissionStartedThisPoll = false;
        activeOrderId = null;
        nextOrderNumber++;
        RESET_OFFER.discard();
        RESET_OFFER.begin(resetId, nowMillis);
        updateResetUi("Resetting system...", false);
        return true;
    }

    /** Returns a bounded reliable copy of the active reset identity. */
    public static synchronized String pollSystemResetRequest() {
        if (!resetInProgress && !testResetRequested &&
            TEST_RESET_DELAY_MILLIS >= 0L &&
            System.currentTimeMillis() >=
                START_MILLIS + TEST_RESET_DELAY_MILLIS) {
            testResetRequested = true;
            requestSystemReset();
        }
        return pollSystemResetRequest(System.currentTimeMillis());
    }

    static synchronized String pollSystemResetRequest(long nowMillis) {
        return RESET_OFFER.nextValue(nowMillis);
    }

    public static synchronized boolean isSystemResetTransmissionStart() {
        return RESET_OFFER.isTransmissionStarted();
    }

    /** Accepts a matching local reset or a first-seen external system reset. */
    public static synchronized String handleSystemResetComplete(String payload) {
        final String suffix = "|RESET_COMPLETE";
        if (payload == null || !payload.endsWith(suffix)) {
            return null;
        }
        String completedResetId = payload.substring(
            0, payload.length() - suffix.length()
        );
        if (!completedResetId.matches("RST[0-9]{4,}") ||
            COMPLETED_RESET_IDS.contains(completedResetId)) {
            return null;
        }

        boolean locallyInitiated = resetInProgress &&
            completedResetId.equals(activeResetId);
        if (!locallyInitiated) {
            nextOrderNumber++;
        }
        COMPLETED_RESET_IDS.add(completedResetId);
        RESET_OFFER.discard();
        PENDING_ORDER.set(null);
        pendingTransmissionPayload = null;
        orderTransmissionsRemaining = 0;
        nextOrderTransmissionMillis = 0L;
        activeTransmissionPayload = null;
        activeTransmissionUntilMillis = 0L;
        transmissionStartedThisPoll = false;
        activeOrderId = null;
        activeResetId = null;
        resetInProgress = false;
        nextTestOrderMillis =
            System.currentTimeMillis() + TEST_ORDER_INTERVAL_MILLIS;
        updateResetUi(
            (locallyInitiated ? "Reset complete" :
                "External system reset complete") +
                " - ready for " + nextOrderId(),
            true
        );
        return "POS received system reset completion: " + completedResetId;
    }

    private static void updateResetUi(
        final String message,
        final boolean complete
    ) {
        final POSVisualisation ui = instance;
        if (ui == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ui.orderIdField.setText(nextOrderId());
                ui.submissionStatus.setForeground(
                    complete ? new Color(20, 120, 55) : new Color(180, 105, 20)
                );
                ui.submissionStatus.setText(message);
                ui.completionStatus.setForeground(new Color(90, 90, 90));
                ui.completionStatus.setText(
                    "<html><div style='text-align:center'>" +
                    (complete ? "System reset complete" : "Reset pending external ACKs") +
                    "</div></html>"
                );
                ui.submitButton.setEnabled(complete);
                ui.resetButton.setEnabled(complete);
            }
        });
    }

    static synchronized void resetForTest() {
        nextWatchdogHeartbeatMillis = 0L;
        PENDING_ORDER.set(null);
        RESET_OFFER.discard();
        testOrdersReturned = 0;
        testResetRequested = false;
        nextOrderNumber = 1;
        nextResetNumber = 1;
        activeOrderId = null;
        pendingTransmissionPayload = null;
        orderTransmissionsRemaining = 0;
        lastOrderTransmissionAttempt = 0;
        nextOrderTransmissionMillis = 0L;
        activeTransmissionPayload = null;
        activeTransmissionUntilMillis = 0L;
        transmissionStartedThisPoll = false;
        resetInProgress = false;
        activeResetId = null;
        COMPLETED_ORDER_IDS.clear();
        COMPLETED_RESET_IDS.clear();
    }

    static synchronized boolean isResetInProgressForTest() {
        return resetInProgress;
    }

    static synchronized String nextOrderIdForTest() {
        return nextOrderId();
    }

    static synchronized boolean queueOrderForTest(String payload) {
        return !resetInProgress && OrderV2.parse(payload) != null &&
            PENDING_ORDER.compareAndSet(null, payload);
    }

    private static void updateSubmissionStatus(
        final String message,
        final boolean error
    ) {
        final POSVisualisation ui = instance;
        if (ui == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ui.submissionStatus.setForeground(
                    error ? new Color(175, 35, 35) : new Color(20, 120, 55)
                );
                ui.submissionStatus.setText(message);
            }
        });
    }

    private static void setSubmitEnabled(final boolean enabled) {
        final POSVisualisation ui = instance;
        if (ui == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ui.submitButton.setEnabled(enabled);
            }
        });
    }

    private static boolean isProtocolToken(String text) {
        return text.length() > 0 && text.indexOf('|') < 0 &&
            text.indexOf(',') < 0 && text.indexOf(';') < 0;
    }

    private static String buildOrderPayload(
        String orderId,
        List<ProductInputRow> rows
    ) {
        if (!isProtocolToken(orderId)) {
            throw new IllegalArgumentException(
                "Order ID is required and cannot contain | , ;"
            );
        }
        if (rows == null || rows.size() < 1 ||
            rows.size() > OrderV2.MAX_PRODUCTS) {
            throw new IllegalArgumentException(
                "Order must contain 1 to " + OrderV2.MAX_PRODUCTS +
                " products"
            );
        }

        StringBuilder products = new StringBuilder();
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) {
                products.append(';');
            }
            products.append(rows.get(index).encodedProduct());
        }
        String payload = orderId + "|" + rows.size() + "|" + products;
        if (OrderV2.parse(payload) == null) {
            throw new IllegalArgumentException(
                "Invalid size-aware order was not queued"
            );
        }
        return payload;
    }

    static String buildOrderPayloadForTest(
        String orderId,
        ProductInputRow... rows
    ) {
        List<ProductInputRow> values = new ArrayList<ProductInputRow>();
        if (rows != null) {
            for (ProductInputRow row : rows) {
                values.add(row);
            }
        }
        return buildOrderPayload(orderId, values);
    }

    private static JPanel createProductPanel(ProductInputRow row, int number) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Product " + number));
        GridBagConstraints constraints = baseConstraints();

        addFormLabel(panel, constraints, 0, "Product Type");
        addFormField(panel, constraints, 0, row.productPreset);
        addFormLabel(panel, constraints, 1, "Bottle Size");
        addFormField(panel, constraints, 1, row.bottleSize);
        addFormLabel(panel, constraints, 2, "Quantity");
        addFormField(panel, constraints, 2, row.quantity);
        addFormLabel(panel, constraints, 3, "Liquid A %");
        addFormField(panel, constraints, 3, row.liquidA);
        addFormLabel(panel, constraints, 4, "Liquid B %");
        addFormField(panel, constraints, 4, row.liquidB);
        return panel;
    }

    private static GridBagConstraints baseConstraints() {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(5, 6, 5, 6);
        return constraints;
    }

    private static void addFormLabel(
        JPanel panel,
        GridBagConstraints constraints,
        int row,
        String text
    ) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(text), constraints);
    }

    private static void addFormField(
        JPanel panel,
        GridBagConstraints constraints,
        int row,
        JTextField field
    ) {
        constraints.gridx = 1;
        constraints.gridy = row;
        constraints.gridwidth = 1;
        constraints.weightx = 1.0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, constraints);
    }

    private static void addFormField(
        JPanel panel,
        GridBagConstraints constraints,
        int row,
        JComboBox<?> field
    ) {
        constraints.gridx = 1;
        constraints.gridy = row;
        constraints.gridwidth = 1;
        constraints.weightx = 1.0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, constraints);
    }

    enum ProductPreset {
        P1("P1", 25, 75),
        P2("P2", 50, 50),
        P3("P3", 75, 25),
        CUSTOM("Custom", -1, -1);

        private final String productId;
        private final int liquidA;
        private final int liquidB;

        ProductPreset(String id, int a, int b) {
            productId = id;
            liquidA = a;
            liquidB = b;
        }

        private boolean isCustom() {
            return this == CUSTOM;
        }

        private static ProductPreset matching(
            String productId,
            String liquidA,
            String liquidB
        ) {
            for (ProductPreset preset : values()) {
                if (!preset.isCustom() &&
                    preset.productId.equals(productId) &&
                    Integer.toString(preset.liquidA).equals(liquidA) &&
                    Integer.toString(preset.liquidB).equals(liquidB)) {
                    return preset;
                }
            }
            return CUSTOM;
        }

        @Override
        public String toString() {
            return productId;
        }
    }

    /** One independent product row; the order supports up to four rows. */
    static final class ProductInputRow {
        private final JComboBox<ProductPreset> productPreset;
        private final JTextField productId;
        private final JComboBox<SizeOption> bottleSize;
        private final JTextField quantity;
        private final JTextField liquidA;
        private final JTextField liquidB;
        private ProductPreset activePreset;
        private String customProductId = "";
        private String customLiquidA = "";
        private String customLiquidB = "";

        ProductInputRow(
            String productIdValue,
            String quantityValue,
            String liquidAValue,
            String liquidBValue
        ) {
            productPreset = new JComboBox<ProductPreset>(
                new ProductPreset[] {
                    ProductPreset.P1,
                    ProductPreset.P2,
                    ProductPreset.P3
                }
            );
            productId = new JTextField(productIdValue, 14);
            bottleSize = new JComboBox<SizeOption>(new SizeOption[] {
                new SizeOption("Small \u2014 200 mL", OrderV2.SMALL),
                new SizeOption("Large \u2014 500 mL", OrderV2.LARGE)
            });
            bottleSize.setSelectedIndex(0);
            quantity = new JTextField(quantityValue, 14);
            liquidA = new JTextField(liquidAValue, 14);
            liquidB = new JTextField(liquidBValue, 14);
            activePreset = ProductPreset.matching(
                productIdValue,
                liquidAValue,
                liquidBValue
            );
            if (activePreset.isCustom()) {
                customProductId = productIdValue;
                customLiquidA = liquidAValue;
                customLiquidB = liquidBValue;
            }
            productPreset.setSelectedItem(activePreset);
            applyPresetFields(activePreset);
            productPreset.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    ProductPreset selected =
                        (ProductPreset)productPreset.getSelectedItem();
                    changePreset(selected == null ?
                        ProductPreset.P1 : selected);
                }
            });
        }

        private void changePreset(ProductPreset nextPreset) {
            if (activePreset != null && activePreset.isCustom()) {
                customProductId = productId.getText();
                customLiquidA = liquidA.getText();
                customLiquidB = liquidB.getText();
            }
            activePreset = nextPreset;
            applyPresetFields(nextPreset);
        }

        private void applyPresetFields(ProductPreset preset) {
            boolean custom = preset.isCustom();
            if (custom) {
                productId.setText(customProductId);
                liquidA.setText(customLiquidA);
                liquidB.setText(customLiquidB);
            }
            else {
                productId.setText(preset.productId);
                liquidA.setText(Integer.toString(preset.liquidA));
                liquidB.setText(Integer.toString(preset.liquidB));
            }
            productId.setEditable(custom);
            liquidA.setEditable(custom);
            liquidB.setEditable(custom);
        }

        private String selectedSizeCode() {
            SizeOption selected = (SizeOption)bottleSize.getSelectedItem();
            return selected == null ? OrderV2.SMALL : selected.code;
        }

        private String encodedProduct() {
            String encodedProductId = productId.getText().trim();
            if (!isProtocolToken(encodedProductId)) {
                throw new IllegalArgumentException(
                    "Product is required and cannot contain | , ;"
                );
            }

            final int encodedQuantity;
            final int encodedLiquidA;
            final int encodedLiquidB;
            try {
                encodedQuantity = Integer.parseInt(quantity.getText().trim());
                encodedLiquidA = Integer.parseInt(liquidA.getText().trim());
                encodedLiquidB = Integer.parseInt(liquidB.getText().trim());
            }
            catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                    "Quantity and liquid percentages must be integers"
                );
            }
            if (encodedQuantity <= 0) {
                throw new IllegalArgumentException(
                    "Quantity must be greater than 0"
                );
            }
            if (encodedLiquidA < 0 || encodedLiquidA > 100 ||
                encodedLiquidB < 0 || encodedLiquidB > 100) {
                throw new IllegalArgumentException(
                    "Liquid percentages must be from 0 to 100"
                );
            }
            if (encodedLiquidA + encodedLiquidB != 100) {
                throw new IllegalArgumentException(
                    "Liquid A + Liquid B must equal 100"
                );
            }
            return encodedProductId + ',' + selectedSizeCode() + ',' +
                encodedLiquidA + ',' + encodedLiquidB + ',' + encodedQuantity;
        }

        void selectPresetForTest(ProductPreset preset) {
            if (preset == ProductPreset.CUSTOM) {
                changePreset(preset);
                return;
            }
            productPreset.setSelectedItem(preset);
        }

        void setCustomValuesForTest(String id, String a, String b) {
            if (activePreset != ProductPreset.CUSTOM) {
                selectPresetForTest(ProductPreset.CUSTOM);
            }
            productId.setText(id);
            liquidA.setText(a);
            liquidB.setText(b);
        }

        void setSizeAndQuantityForTest(String sizeCode, String value) {
            bottleSize.setSelectedIndex(OrderV2.LARGE.equals(sizeCode) ? 1 : 0);
            quantity.setText(value);
        }

        String productIdForTest() {
            return productId.getText();
        }

        String liquidAForTest() {
            return liquidA.getText();
        }

        String liquidBForTest() {
            return liquidB.getText();
        }

        boolean customFieldsEditableForTest() {
            return productId.isEditable() && liquidA.isEditable() &&
                liquidB.isEditable();
        }

        int selectablePresetCountForTest() {
            return productPreset.getItemCount();
        }
    }

    private static final class SizeOption {
        private final String label;
        private final String code;

        private SizeOption(String displayedLabel, String protocolCode) {
            label = displayedLabel;
            code = protocolCode;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
