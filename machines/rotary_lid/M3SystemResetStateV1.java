import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** M3 participant in M1's frozen, raw-reset-id acknowledgement barrier. */
public final class M3SystemResetStateV1 {
    private static final Pattern RESET_ID = Pattern.compile("RST([0-9]{4,})");
    private static final long QUIET_MILLIS = 750L;
    private static final int RESET_HISTORY_LIMIT = 64;
    private static final BoundedStringSignalOfferV1 ACK =
        new BoundedStringSignalOfferV1(5, 120L, 80L);
    private static final LinkedHashSet<String> SEEN_RESET_IDS =
        new LinkedHashSet<String>();

    private static volatile String activeResetId;
    private static long quietUntilMillis;
    private static volatile boolean finalised;

    private M3SystemResetStateV1() {
    }

    public static synchronized boolean accept(String resetId) {
        Matcher matcher = RESET_ID.matcher(
            resetId == null ? "" : resetId.trim()
        );
        if (!matcher.matches()) {
            return false;
        }

        String canonical = resetId.trim();
        long now = nowMillis();
        if (canonical.equals(activeResetId)) {
            if (finalised) {
                ACK.begin(canonical, now);
            }
            return true;
        }
        if (SEEN_RESET_IDS.contains(canonical)) {
            return false;
        }

        activeResetId = canonical;
        rememberResetId(canonical);
        quietUntilMillis = now + QUIET_MILLIS;
        finalised = false;
        ACK.discard();
        applySafeReset();
        return true;
    }

    public static synchronized String takeAck() {
        long now = nowMillis();
        if (activeResetId == null) {
            return null;
        }
        if (!finalised && now >= quietUntilMillis) {
            applySafeReset();
            if (!Member3PlantStateV1.isResetSafe() ||
                !Member3MachineStateV1.isResetSafe()) {
                quietUntilMillis = now + QUIET_MILLIS;
                return null;
            }
            finalised = true;
            ACK.begin(activeResetId, now);
        }
        return ACK.nextValue(now);
    }

    public static boolean isQuarantined() {
        return activeResetId != null && !finalised;
    }

    private static void applySafeReset() {
        Member3PlantStateV1.systemReset();
        Member3MachineStateV1.systemReset();
        FaultMonitoringStateV2_1.systemReset();
    }

    private static void rememberResetId(String resetId) {
        if (SEEN_RESET_IDS.size() >= RESET_HISTORY_LIMIT) {
            Iterator<String> oldest = SEEN_RESET_IDS.iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        SEEN_RESET_IDS.add(resetId);
    }

    private static long nowMillis() {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime()
        );
    }
}
