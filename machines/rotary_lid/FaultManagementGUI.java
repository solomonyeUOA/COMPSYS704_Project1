/** Starts the browser-based M3 fault-management dashboard. */
public final class FaultManagementGUI {
    private FaultManagementGUI() {
    }

    public static void start() {
        FaultManagementWebServer.start();
    }
}
