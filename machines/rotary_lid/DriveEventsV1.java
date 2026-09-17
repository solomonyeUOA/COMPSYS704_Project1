import java.util.ArrayDeque;

/** Bounded diagnostic history, also written to the existing process log stream. */
public final class DriveEventsV1 {
    private static final ArrayDeque<String> history = new ArrayDeque<String>();
    private static final ArrayDeque<Notification> notifications = new ArrayDeque<Notification>();
    private static long sequence;
    private DriveEventsV1() { }
    public static synchronized void record(String module, String action, int count,
        String result, boolean safeStop) {
        String entry = java.time.Instant.now() + " module=" + module +
            " action=" + action + " switchCount=" + count + " result=" + result +
            " safeStopRequired=" + safeStop;
        if (history.size() == 200) history.removeFirst();
        history.addLast(entry);
        if (action.equals("FAULT_DETECTED") || action.startsWith("SWITCH_TO_") ||
            action.equals("FAILOVER_VERIFIED") || action.equals("SAFE_ERROR") ||
            action.equals("ACTION_INTERRUPTED") || action.equals("TRANSFER_PHASE")) {
            if (notifications.size() == 200) notifications.removeFirst();
            notifications.addLast(new Notification(++sequence, module, action, entry, safeStop));
        }
        System.out.println("[M3-DRIVE] " + entry);
    }
    public static synchronized String[] snapshot() {
        return history.toArray(new String[history.size()]);
    }
    public static synchronized Notification[] notificationsAfter(long after) {
        java.util.ArrayList<Notification> result = new java.util.ArrayList<Notification>();
        for (Notification notification : notifications)
            if (notification.sequence > after) result.add(notification);
        return result.toArray(new Notification[result.size()]);
    }
    public static synchronized long notificationSequence() { return sequence; }
    public static final class Notification {
        public final long sequence;
        public final String module, action, message;
        public final boolean safeStop;
        Notification(long sequence, String module, String action, String message, boolean safeStop) {
            this.sequence = sequence;
            this.module = module;
            this.action = action;
            this.message = message;
            this.safeStop = safeStop;
        }
    }
}
