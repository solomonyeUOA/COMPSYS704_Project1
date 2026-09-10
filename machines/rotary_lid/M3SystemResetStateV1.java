import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** M3 participant in M1's frozen, raw-reset-id acknowledgement barrier. */
public final class M3SystemResetStateV1 {
    private static final Pattern RESET_ID = Pattern.compile("RST([0-9]{4,})");
    private static final long QUIET_MILLIS = 750L;
    private static final BoundedStringSignalOfferV1 ACK =
        new BoundedStringSignalOfferV1(5, 120L, 80L);

    private static volatile String activeResetId;
    private static long highestSequence = -1L;
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

        final long sequence;
        try {
            sequence = Long.parseLong(matcher.group(1));
        }
        catch (NumberFormatException invalid) {
            return false;
        }

        String canonical = resetId.trim();
        long now = nowMillis();
        if (sequence < highestSequence) {
            return false;
        }
        if (sequence == highestSequence) {
            if (!canonical.equals(activeResetId)) {
                return false;
            }
            if (finalised) {
                ACK.begin(canonical, now);
            }
            return true;
        }

        activeResetId = canonical;
        highestSequence = sequence;
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

    private static long nowMillis() {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime()
        );
    }
}
