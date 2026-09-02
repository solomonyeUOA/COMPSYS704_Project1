import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Read-only M4 simulation view; it never sends an actuator command. */
public final class Member4Visualisation {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private Member4Visualisation() {
    }

    public static void start() {
        if (GraphicsEnvironment.isHeadless() ||
            !STARTED.compareAndSet(false, true)) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                createWindow();
            }
        });
    }

    private static void createWindow() {
        final JFrame frame = new JFrame("Member 4 Filling, Capping and Sort/Pack");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        final JTextArea state = new JTextArea();
        state.setEditable(false);
        state.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        state.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        final ProcessPanel process = new ProcessPanel();
        process.setPreferredSize(new Dimension(920, 250));
        frame.add(process, BorderLayout.CENTER);
        frame.add(new JScrollPane(state), BorderLayout.SOUTH);

        Timer refresh = new Timer(200, event -> {
            state.setText(
                Member4MachineStateV1.snapshot() + "\n" +
                Member4PlantStateV1.snapshot()
            );
            process.repaint();
        });
        refresh.start();

        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    private static final class ProcessPanel extends JPanel {
        private static final long serialVersionUID = 1L;

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            );
            String[] titles = {
                "Filler A", "Filler B", "Capper", "Sort / Pack"
            };
            String[] details = {
                Member4MachineStateV1.fillerASnapshot(),
                Member4MachineStateV1.fillerBSnapshot(),
                Member4MachineStateV1.capperSnapshot(),
                Member4MachineStateV1.sortPackSnapshot()
            };
            int gap = 18;
            int width = (getWidth() - gap * 5) / 4;
            for (int i = 0; i < titles.length; i++) {
                int x = gap + i * (width + gap);
                drawStation(g2, x, 35, width, 165, titles[i], details[i]);
                if (i < titles.length - 1) {
                    g2.setColor(new Color(46, 116, 181));
                    int arrowX = x + width + 4;
                    g2.drawLine(arrowX, 115, arrowX + gap - 8, 115);
                    g2.drawLine(arrowX + gap - 12, 111,
                        arrowX + gap - 8, 115);
                    g2.drawLine(arrowX + gap - 12, 119,
                        arrowX + gap - 8, 115);
                }
            }
            g2.dispose();
        }

        private void drawStation(
            Graphics2D g2,
            int x,
            int y,
            int width,
            int height,
            String title,
            String detail
        ) {
            Color fill = detail.contains("FAULT") ?
                new Color(255, 226, 226) : new Color(231, 242, 242);
            g2.setColor(fill);
            g2.fillRoundRect(x, y, width, height, 18, 18);
            g2.setColor(new Color(23, 54, 93));
            g2.drawRoundRect(x, y, width, height, 18, 18);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
            g2.drawString(title, x + 12, y + 25);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            String compact = detail.length() > 88 ?
                detail.substring(0, 88) + "..." : detail;
            int split = Math.min(44, compact.length());
            g2.drawString(compact.substring(0, split), x + 12, y + 55);
            if (split < compact.length()) {
                g2.drawString(compact.substring(split), x + 12, y + 73);
            }

            g2.setColor(new Color(255, 255, 255));
            g2.fillRoundRect(x + width / 2 - 25, y + 90, 50, 58, 12, 12);
            g2.setColor(new Color(46, 116, 181));
            g2.drawRoundRect(x + width / 2 - 25, y + 90, 50, 58, 12, 12);
            if (detail.contains("GEOM_L")) {
                g2.drawString("500 mL", x + width / 2 - 21, y + 121);
            }
            else if (detail.contains("GEOM_S")) {
                g2.drawString("200 mL", x + width / 2 - 21, y + 121);
            }
            else {
                g2.drawString("waiting", x + width / 2 - 19, y + 121);
            }
        }
    }
}
