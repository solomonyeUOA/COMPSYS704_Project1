import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Read-only Swing view of the Member 3 Plant state. */
public final class Member3Visualisation {
    private static boolean started;

    private Member3Visualisation() {
    }

    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("Member 3 visualisation disabled in headless mode");
            return;
        }

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                JFrame frame = new JFrame("ABS - Rotary Table and Lid Loader");
                final PlantPanel panel = new PlantPanel();
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.setContentPane(panel);
                frame.pack();
                frame.setLocationByPlatform(true);
                frame.setVisible(true);
                new Timer(100, event -> panel.repaint()).start();
            }
        });
    }

    private static final class PlantPanel extends JPanel {
        private static final int WIDTH = 760;
        private static final int HEIGHT = 560;
        private static final int CENTER_X = 300;
        private static final int CENTER_Y = 285;
        private static final int TABLE_RADIUS = 175;

        PlantPanel() {
            setPreferredSize(new Dimension(WIDTH, HEIGHT));
            setBackground(new Color(245, 247, 249));
        }

        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

            g.setColor(new Color(31, 41, 55));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
            g.drawString("Rotary Table", 24, 34);

            g.setColor(new Color(218, 223, 229));
            g.fillOval(CENTER_X - TABLE_RADIUS, CENTER_Y - TABLE_RADIUS,
                TABLE_RADIUS * 2, TABLE_RADIUS * 2);
            g.setColor(new Color(75, 85, 99));
            g.setStroke(new BasicStroke(3));
            g.drawOval(CENTER_X - TABLE_RADIUS, CENTER_Y - TABLE_RADIUS,
                TABLE_RADIUS * 2, TABLE_RADIUS * 2);

            for (int i = 0; i < 6; i++) {
                double angle = Math.toRadians(-150 + i * 60);
                int x = CENTER_X + (int) (Math.cos(angle) * 135);
                int y = CENTER_Y + (int) (Math.sin(angle) * 135);
                drawPosition(g, i, x, y);
            }

            g.setColor(new Color(31, 41, 55));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            g.drawString("Lid Loader", 530, 90);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
            g.drawString("Magazine: " +
                Member3PlantStateV1.getLidMagazineCount(), 530, 125);
            g.drawString("Action: " +
                Member3PlantStateV1.getLidActionName(), 530, 152);
            g.drawString("Rotary: " + Member3MachineStateV1.statusName(
                Member3MachineStateV1.getRotaryStatus()), 530, 205);
            g.drawString("Lid: " + Member3MachineStateV1.statusName(
                Member3MachineStateV1.getLidStatus()), 530, 232);

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g.drawString("P1 Load", 24, 505);
            g.drawString("P2 Fill", 115, 505);
            g.drawString("P3 Lid", 205, 505);
            g.drawString("P4 Cap", 295, 505);
            g.drawString("P5 Transfer", 385, 505);
            g.drawString("P6 Label", 485, 505);
            g.dispose();
        }

        private void drawPosition(Graphics2D g, int index, int x, int y) {
            String label = Member3PlantStateV1.positionLabel(index);
            boolean occupied = !"empty".equals(label);
            g.setColor(occupied ? new Color(29, 120, 116) : Color.WHITE);
            g.fillOval(x - 43, y - 43, 86, 86);
            g.setColor(new Color(55, 65, 81));
            g.setStroke(new BasicStroke(2));
            g.drawOval(x - 43, y - 43, 86, 86);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g.setColor(occupied ? Color.WHITE : new Color(75, 85, 99));
            g.drawString("P" + (index + 1), x - 8, y - 5);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            String text = label.length() > 14 ? label.substring(0, 14) : label;
            g.drawString(text, x - 36, y + 15);
        }
    }
}
