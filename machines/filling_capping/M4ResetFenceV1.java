import java.util.HashSet;
import java.util.Set;

/** JVM-lifetime bottle tombstones; a production reset must never clear these. */
public final class M4ResetFenceV1 {
    private static volatile boolean quarantined;
    private static final Set<String> observed = new HashSet<String>();
    private static final Set<String> retired = new HashSet<String>();
    private static final Set<String> retiredPrefixes = new HashSet<String>();

    private M4ResetFenceV1() { }

    public static boolean isQuarantined() { return quarantined; }

    public static synchronized void begin() {
        quarantined = true;
        retired.addAll(observed);
        observed.clear();
    }

    public static void release() { quarantined = false; }

    public static synchronized void retirePrefix(String prefix) {
        retiredPrefixes.add(prefix);
    }

    public static synchronized boolean accept(String payload) {
        if (payload == null) { return false; }
        String bottle = payload.split("\\|", -1)[0];
        if (quarantined) {
            retired.add(bottle);
            return false;
        }
        if (retired.contains(bottle)) { return false; }
        for (String prefix : retiredPrefixes) {
            if (bottle.startsWith(prefix)) { return false; }
        }
        observed.add(bottle);
        return true;
    }
}
