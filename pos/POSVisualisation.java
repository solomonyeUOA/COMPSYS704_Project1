import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Handwritten Swing UI for POSCD. This class never opens a network socket;
 * POSCD remains the only sender/receiver of the frozen SystemJ protocol.
 */
public final class POSVisualisation {
    private static final String TEST_ORDER_PROPERTY = "abs.pos.testOrder";
    private static final long TEST_ORDER_DELAY_MILLIS = 5000;
    private static final long START_MILLIS = System.currentTimeMillis();
    private static final AtomicReference<String> PENDING_ORDER =
        new AtomicReference<String>();

    private static volatile POSVisualisation instance;
    private static boolean testOrderReturned = false;
    private static String activeOrderId = "";
    private static String handledCompletionPayload = "";

    private final JFrame frame;
    private final JTextField orderIdField;
    private final List<ProductInputRow> productRows;
    private final JButton submitButton;
    private final JLabel submissionStatus;
    private final JLabel completionStatus;

    private POSVisualisation() {
        frame = new JFrame("Purchase Order System");
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
        orderIdField = new JTextField("PO001", 18);
        addFormField(form, constraints, 0, orderIdField);

        productRows = new ArrayList<ProductInputRow>();
        ProductInputRow firstProduct = new ProductInputRow(
            "P1", "2", "60", "40"
        );
        productRows.add(firstProduct);
        JPanel productPanel = createProductPanel(firstProduct, 1);
        constraints.gridx = 0;
        constraints.gridy = 1;
        constraints.gridwidth = 2;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1.0;
        constraints.insets = new Insets(10, 0, 8, 0);
        form.add(productPanel, constraints);

        submitButton = new JButton("Submit Order");
        submitButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                queueOrderFromForm();
            }
        });
        constraints.gridy = 2;
        constraints.insets = new Insets(8, 0, 4, 0);
        form.add(submitButton, constraints);

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
        frame.setPreferredSize(new Dimension(470, 500));
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

    /**
     * Returns one UI order, or a delayed test-only order supplied with
     * -Dabs.pos.testOrder=... . Normal GUI runs have no automatic order.
     */
    public static synchronized String pollSubmittedOrder() {
        String pendingOrder = PENDING_ORDER.getAndSet(null);
        if (pendingOrder != null) {
            return pendingOrder;
        }

        String testOrder = System.getProperty(TEST_ORDER_PROPERTY, "").trim();
        if (!testOrderReturned && testOrder.length() > 0 &&
            System.currentTimeMillis() >=
            START_MILLIS + TEST_ORDER_DELAY_MILLIS) {
            testOrderReturned = true;
            return testOrder;
        }
        return null;
    }

    public static synchronized void showSubmitted(final String payload) {
        int separator = payload.indexOf('|');
        activeOrderId = separator > 0 ? payload.substring(0, separator) : "";
        handledCompletionPayload = "";
        updateSubmissionStatus("Order submitted: " + activeOrderId, false);
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
        if (payload != null && payload.equals(handledCompletionPayload)) {
            return null;
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

        if (!completedOrderId.equals(activeOrderId) ||
            !"COMPLETED".equals(completionState) || !validSeconds) {
            showValidationError("Invalid ORDER_COMPLETE received");
            return "POS received invalid ORDER_COMPLETE: " + payload;
        }

        handledCompletionPayload = payload;
        showCompletion(completedOrderId, completionState, completionSeconds);
        return "POS received completion: orderId=" + completedOrderId +
            ", status=" + completionState +
            ", completionTime=" + completionSeconds + " seconds";
    }

    private void queueOrderFromForm() {
        String orderId = orderIdField.getText().trim();
        if (!isProtocolToken(orderId)) {
            showValidationError("Order ID is required and cannot contain | , ;");
            return;
        }

        StringBuilder products = new StringBuilder();
        for (int index = 0; index < productRows.size(); index++) {
            ProductInputRow row = productRows.get(index);
            String productId = row.productId.getText().trim();
            if (!isProtocolToken(productId)) {
                showValidationError(
                    "Product is required and cannot contain | , ;"
                );
                return;
            }

            final int quantity;
            final int liquidA;
            final int liquidB;
            try {
                quantity = Integer.parseInt(row.quantity.getText().trim());
                liquidA = Integer.parseInt(row.liquidA.getText().trim());
                liquidB = Integer.parseInt(row.liquidB.getText().trim());
            }
            catch (NumberFormatException error) {
                showValidationError(
                    "Quantity and liquid percentages must be integers"
                );
                return;
            }

            if (quantity <= 0) {
                showValidationError("Quantity must be greater than 0");
                return;
            }
            if (liquidA < 0 || liquidA > 100 ||
                liquidB < 0 || liquidB > 100) {
                showValidationError("Liquid percentages must be from 0 to 100");
                return;
            }
            if (liquidA + liquidB != 100) {
                showValidationError("Liquid A + Liquid B must equal 100");
                return;
            }

            if (index > 0) {
                products.append(';');
            }
            products.append(productId).append(',')
                .append(liquidA).append(',')
                .append(liquidB).append(',')
                .append(quantity);
        }

        String payload = orderId + "|" + productRows.size() + "|" + products;
        if (!PENDING_ORDER.compareAndSet(null, payload)) {
            showValidationError("An order is already waiting to be submitted");
            return;
        }

        submitButton.setEnabled(false);
        submissionStatus.setForeground(new Color(35, 90, 155));
        submissionStatus.setText("Submitting order...");
    }

    private static void showCompletion(
        final String orderId,
        final String state,
        final String seconds
    ) {
        final POSVisualisation ui = instance;
        if (ui == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ui.completionStatus.setText(
                    "<html><div style='text-align:center'>" +
                    "Order ID: " + orderId + "<br>" +
                    "Status: " + state + "<br>" +
                    "Completion Time: " + seconds + " seconds" +
                    "</div></html>"
                );
                ui.completionStatus.setForeground(new Color(20, 120, 55));
                ui.submissionStatus.setText("Order completed");

                Timer enableTimer = new Timer(1500, new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        ui.submitButton.setEnabled(true);
                    }
                });
                enableTimer.setRepeats(false);
                enableTimer.start();
            }
        });
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

    private static JPanel createProductPanel(ProductInputRow row, int number) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Product " + number));
        GridBagConstraints constraints = baseConstraints();

        addFormLabel(panel, constraints, 0, "Product");
        addFormField(panel, constraints, 0, row.productId);
        addFormLabel(panel, constraints, 1, "Quantity");
        addFormField(panel, constraints, 1, row.quantity);
        addFormLabel(panel, constraints, 2, "Liquid A %");
        addFormField(panel, constraints, 2, row.liquidA);
        addFormLabel(panel, constraints, 3, "Liquid B %");
        addFormField(panel, constraints, 3, row.liquidB);
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

    /** One row today; adding up to four rows does not change ORDER encoding. */
    private static final class ProductInputRow {
        private final JTextField productId;
        private final JTextField quantity;
        private final JTextField liquidA;
        private final JTextField liquidB;

        private ProductInputRow(
            String productIdValue,
            String quantityValue,
            String liquidAValue,
            String liquidBValue
        ) {
            productId = new JTextField(productIdValue, 14);
            quantity = new JTextField(quantityValue, 14);
            liquidA = new JTextField(liquidAValue, 14);
            liquidB = new JTextField(liquidBValue, 14);
        }
    }
}
