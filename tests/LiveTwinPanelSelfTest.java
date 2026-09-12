import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.TableModel;

/** Real Swing tables, using explicit test fixtures rather than runtime evidence. */
public final class LiveTwinPanelSelfTest {
    private static int assertions;

    private LiveTwinPanelSelfTest() { }

    public static void main(final String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException("Optional argument: PNG output directory");
        }
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                try {
                    exercisePanels(args.length == 0 ? null : new File(args[0]));
                }
                catch (Exception exception) { throw new RuntimeException(exception); }
            }
        });
        System.out.println("LiveTwinPanelSelfTest PASSED assertions=" + assertions +
            " (headless Swing test fixtures; not a live runtime test)");
    }

    private static void exercisePanels(File output) throws Exception {
        require(SwingUtilities.isEventDispatchThread(), "all UI work runs on EDT");
        ABSVisualisation.resetSystem("RST9000");
        ABSVisualisation.TeamIpDetailPanel m2 = new ABSVisualisation.TeamIpDetailPanel(
            ABSVisualisationTeamIpModel.M2_DIGITAL_TWIN);
        ABSVisualisation.TeamIpDetailPanel m4 = new ABSVisualisation.TeamIpDetailPanel(
            ABSVisualisationTeamIpModel.M4_TWO_SIZE);
        assertEmpty(m2);
        assertEmpty(m4);

        String fixture = fixture("9001", 1L);
        ABSVisualisation.updateTwinSnapshot(fixture);
        m2.syncState();
        m4.syncState();
        assertPopulated(m2);
        assertPopulated(m4);

        // A duplicate or invalid snapshot cannot erase or duplicate the visible rows.
        ABSVisualisation.updateTwinSnapshot(fixture);
        ABSVisualisation.updateTwinSnapshot(fixture.replace("W=2", "W=3"));
        m2.syncState();
        assertPopulated(m2);
        if (output != null) {
            render(m2, "Live workpieces", output, "live-workpieces-fixture.png");
            render(m2, "Live resources", output, "live-resources-fixture.png");
        }

        // ResourceTwin is one latest row per machine, not a first-bottle cache.
        // Both M2 and M4 tabs must advance when the next bottles use the resources.
        ABSVisualisation.updateTwinSnapshot(secondBottleFixture("9001", 2L));
        m2.syncState();
        m4.syncState();
        assertSecondBottle(m2);
        assertSecondBottle(m4);
        ABSVisualisation.updateTwinSnapshot(fixture);
        m2.syncState();
        m4.syncState();
        assertSecondBottle(m2);
        assertSecondBottle(m4);

        ABSVisualisation.resetSystem("RST9001");
        m2.syncState();
        m4.syncState();
        assertEmpty(m2);
        assertEmpty(m4);
        ABSVisualisation.updateTwinSnapshot(fixture("9001", 999L));
        m2.syncState();
        assertEmpty(m2);

        // Producer generation is reset number + 1; fresh evidence can repopulate.
        ABSVisualisation.updateTwinSnapshot(fixture("9002", 1L));
        m2.syncState();
        m4.syncState();
        assertPopulated(m2);
        assertPopulated(m4);
        ABSVisualisation.updateTwinSnapshot(
            "V2|TWIN|9002|2|W=0|R=0|REJECTED=0|WORKPIECES=|RESOURCES=");
        m2.syncState();
        m4.syncState();
        assertEmpty(m2);
        assertEmpty(m4);
        if (output != null) {
            render(m2, "Live workpieces", output, "live-twins-cleared-fixture.png");
        }
    }

    private static String fixture(String generation, long sequence) {
        return "V2|TWIN|" + generation + "|" + sequence +
            "|W=2|R=3|REJECTED=0|WORKPIECES=" +
            "TEST-S-B001,LABELLED,LABELLER-1,8,S,200;" +
            "TEST-L-B001,COMPLETE,SORT_PACK,11,L,500|RESOURCES=" +
            "CONVEYOR-1,CONVEYOR,TEST-S-B001,2,TRANSFER,-,4;" +
            "LABELLER-1,LABELLER,TEST-S-B001,3,LABEL_VERIFIED,-,5;" +
            "SORT_PACK,SORTPACK,TEST-L-B001,3,OBSERVED_SORTED,-,6";
    }

    private static void assertPopulated(ABSVisualisation.TeamIpDetailPanel panel) {
        JTable bottles = table(panel, "Live workpieces");
        JTable resources = table(panel, "Live resources");
        assertColumns(bottles, new String[] {
            "Bottle", "Stage", "Resource", "Version", "Size", "Capacity mL"});
        assertColumns(resources, new String[] {
            "Resource", "Type", "Current / last bottle", "Status", "Operation",
            "Fault", "Version", "Evidence"});
        require(bottles.getModel().getRowCount() == 2, "both bottle rows displayed");
        require(resources.getModel().getRowCount() == 3, "all resource rows displayed");
        assertRow(bottles.getModel(), 0, new String[] {
            "TEST-S-B001", "LABELLED", "LABELLER-1", "8", "S", "200"});
        assertRow(bottles.getModel(), 1, new String[] {
            "TEST-L-B001", "COMPLETE", "SORT_PACK", "11", "L", "500"});
        assertRow(resources.getModel(), 0, new String[] {
            "CONVEYOR-1", "CONVEYOR", "TEST-S-B001", "BUSY", "TRANSFER", "-", "4",
            "Controller observation"});
        assertRow(resources.getModel(), 1, new String[] {
            "LABELLER-1", "LABELLER", "TEST-S-B001", "DONE", "LABEL_VERIFIED", "-", "5",
            "Controller observation"});
        assertRow(resources.getModel(), 2, new String[] {
            "SORT_PACK", "SORTPACK", "TEST-L-B001", "DONE", "OBSERVED_SORTED", "-", "6",
            "Last confirmed operation"});
        assertReadOnly(bottles);
        assertReadOnly(resources);
        require(bottles.getRowSorter() != null && resources.getRowSorter() != null,
            "both views support read-only sorting");
    }

    private static String secondBottleFixture(String generation, long sequence) {
        return "V2|TWIN|" + generation + "|" + sequence +
            "|W=4|R=3|REJECTED=0|WORKPIECES=" +
            "TEST-S-B001,COMPLETE,SORT_PACK,11,S,200;" +
            "TEST-L-B001,COMPLETE,SORT_PACK,11,L,500;" +
            "TEST-S-B002,LABELLED,LABELLER-1,8,S,200;" +
            "TEST-L-B002,COMPLETE,SORT_PACK,11,L,500|RESOURCES=" +
            "CONVEYOR-1,CONVEYOR,TEST-S-B002,2,TRANSFER,-,14;" +
            "LABELLER-1,LABELLER,TEST-S-B002,3,LABEL_VERIFIED,-,15;" +
            "SORT_PACK,SORTPACK,TEST-L-B002,3,OBSERVED_SORTED,-,16";
    }

    private static void assertSecondBottle(ABSVisualisation.TeamIpDetailPanel panel) {
        JTable bottles = table(panel, "Live workpieces");
        JTable resources = table(panel, "Live resources");
        require(bottles.getModel().getRowCount() == 4,
            "old bottle history and second bottles each remain once");
        require(resources.getModel().getRowCount() == 3,
            "resources stay one current/latest row per machine");
        assertRow(bottles.getModel(), 2, new String[] {
            "TEST-S-B002", "LABELLED", "LABELLER-1", "8", "S", "200"});
        assertRow(bottles.getModel(), 3, new String[] {
            "TEST-L-B002", "COMPLETE", "SORT_PACK", "11", "L", "500"});
        assertRow(resources.getModel(), 0, new String[] {
            "CONVEYOR-1", "CONVEYOR", "TEST-S-B002", "BUSY", "TRANSFER", "-", "14",
            "Controller observation"});
        assertRow(resources.getModel(), 1, new String[] {
            "LABELLER-1", "LABELLER", "TEST-S-B002", "DONE", "LABEL_VERIFIED", "-", "15",
            "Controller observation"});
        assertRow(resources.getModel(), 2, new String[] {
            "SORT_PACK", "SORTPACK", "TEST-L-B002", "DONE", "OBSERVED_SORTED", "-", "16",
            "Last confirmed operation"});
        assertReadOnly(bottles);
        assertReadOnly(resources);
    }

    private static void assertEmpty(ABSVisualisation.TeamIpDetailPanel panel) {
        require(table(panel, "Live workpieces").getRowCount() == 0, "workpiece view cleared");
        require(table(panel, "Live resources").getRowCount() == 0, "resource view cleared");
    }

    private static void assertColumns(JTable table, String[] expected) {
        require(table.getColumnCount() == expected.length, "column count");
        for (int col = 0; col < expected.length; col++) {
            require(expected[col].equals(table.getColumnName(col)), "column " + expected[col]);
        }
    }

    private static void assertRow(TableModel model, int row, String[] expected) {
        for (int col = 0; col < expected.length; col++) {
            require(expected[col].equals(model.getValueAt(row, col)),
                "row " + row + " column " + col + " must show snapshot evidence");
        }
    }

    private static void assertReadOnly(JTable table) {
        for (int row = 0; row < table.getRowCount(); row++) {
            for (int col = 0; col < table.getColumnCount(); col++) {
                require(!table.getModel().isCellEditable(row, col), "read-only table model");
                require(!table.editCellAt(row, col), "no Swing editor can be opened");
            }
        }
        require(!table.isEditing(), "table remains observational");
    }

    private static JTable table(Container panel, String title) {
        JTabbedPane tabs = findTabs(panel);
        require(tabs != null, "detail exposes tabbed twin views");
        int tab = tabs.indexOfTab(title);
        require(tab >= 0, "tab exists: " + title);
        Component component = tabs.getComponentAt(tab);
        require(component instanceof JScrollPane, "table is independently scrollable");
        Component view = ((JScrollPane)component).getViewport().getView();
        require(view instanceof JTable, "actual JTable is displayed");
        return (JTable)view;
    }

    private static JTabbedPane findTabs(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JTabbedPane) return (JTabbedPane)child;
            if (child instanceof Container) {
                JTabbedPane result = findTabs((Container)child);
                if (result != null) return result;
            }
        }
        return null;
    }

    /** Native Swing painting of a labelled test fixture, never a runtime screenshot. */
    private static void render(ABSVisualisation.TeamIpDetailPanel panel, String tab,
                               File directory, String name) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalArgumentException("Cannot create render directory " + directory);
        }
        findTabs(panel).setSelectedIndex(findTabs(panel).indexOfTab(tab));
        JPanel wrapper = new JPanel(new BorderLayout());
        JLabel label = new JLabel("TEST FIXTURE - native Swing render, not live production evidence");
        label.setOpaque(true);
        label.setBackground(new Color(255, 245, 205));
        label.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        wrapper.add(label, BorderLayout.NORTH);
        wrapper.add(panel, BorderLayout.CENTER);
        wrapper.setSize(1100, 600);
        wrapper.addNotify();
        try {
            layoutTree(wrapper);
            BufferedImage image = new BufferedImage(1100, 600, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try { wrapper.printAll(graphics); }
            finally { graphics.dispose(); }
            File target = new File(directory, name);
            require(ImageIO.write(image, "png", target), "PNG renderer is available");
            System.out.println("TEST_FIXTURE_RENDER " + target.getAbsolutePath());
        }
        finally {
            wrapper.removeNotify();
            wrapper.remove(panel);
        }
    }

    private static void layoutTree(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) {
            if (child instanceof Container) layoutTree((Container)child);
        }
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
