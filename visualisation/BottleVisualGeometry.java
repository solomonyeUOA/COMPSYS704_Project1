/**
 * Immutable, presentation-only bottle geometry for the two M4 size profiles.
 *
 * Screen coordinates grow downwards.  Both profiles are therefore calculated
 * from a common bottle base: the small bottle has a larger top/working Y value
 * (a visibly lower working height), while the large bottle has a smaller one.
 * This class has no Controller/SystemJ responsibilities.
 */
final class BottleVisualGeometry {
    static final double SMALL_SCALE = 0.82;
    static final double LARGE_SCALE = 1.18;
    static final double UNKNOWN_SCALE = 1.00;

    private static final double BODY_RATIO = 0.82;
    private static final double LABEL_CENTRE_RATIO = 0.52;
    private static final double INTERPOLATION_FACTOR = 0.24;
    private static final double MAX_INTERPOLATION_STEP = 8.0;

    private final String sizeCode;
    private final int capacityMl;
    private final double visualScale;

    private BottleVisualGeometry(
        String code,
        int capacity,
        double scale
    ) {
        sizeCode = code;
        capacityMl = capacity;
        visualScale = scale;
    }

    static BottleVisualGeometry forSizeCode(String sizeCode) {
        if ("S".equalsIgnoreCase(safe(sizeCode))) {
            return new BottleVisualGeometry("S", 200, SMALL_SCALE);
        }
        if ("L".equalsIgnoreCase(safe(sizeCode))) {
            return new BottleVisualGeometry("L", 500, LARGE_SCALE);
        }
        return new BottleVisualGeometry("--", 0, UNKNOWN_SCALE);
    }

    static boolean isSupportedSize(String sizeCode) {
        return "S".equalsIgnoreCase(safe(sizeCode)) ||
            "L".equalsIgnoreCase(safe(sizeCode));
    }

    String getSizeCode() { return sizeCode; }
    int getCapacityMl() { return capacityMl; }
    double getVisualScale() { return visualScale; }
    boolean isKnown() { return capacityMl > 0; }

    String getDisplayLabel() {
        if (!isKnown()) return "--";
        return ("S".equals(sizeCode) ? "Small (S)" : "Large (L)") +
            " - " + capacityMl + " mL";
    }

    int bodyWidth(int nominalWidth) {
        return scaled(nominalWidth);
    }

    int totalHeight(int nominalHeight) {
        return scaled(nominalHeight);
    }

    int bodyHeight(int nominalHeight) {
        return Math.max(1, (int)Math.round(
            totalHeight(nominalHeight) * BODY_RATIO
        ));
    }

    int neckHeight(int nominalHeight) {
        return Math.max(1, totalHeight(nominalHeight) -
            bodyHeight(nominalHeight));
    }

    int capHeight(int nominalHeight) {
        return Math.max(3, (int)Math.round(
            totalHeight(nominalHeight) * 0.045
        ));
    }

    double topY(double bottleBaseY, int nominalHeight) {
        return bottleBaseY - totalHeight(nominalHeight);
    }

    double mouthY(double bottleBaseY, int nominalHeight) {
        return topY(bottleBaseY, nominalHeight) +
            Math.max(1.0, totalHeight(nominalHeight) * 0.018);
    }

    double labelCenterY(double bottleBaseY, int nominalHeight) {
        return topY(bottleBaseY, nominalHeight) +
            totalHeight(nominalHeight) * LABEL_CENTRE_RATIO;
    }

    java.awt.geom.Rectangle2D labelBounds(double centreX, double baseY,
                                          int nominalWidth, int nominalHeight) {
        double width = Math.max(1, bodyWidth(nominalWidth) - 3);
        double height = Math.max(3, totalHeight(nominalHeight) * 0.25);
        return new java.awt.geom.Rectangle2D.Double(centreX - width / 2,
            labelCenterY(baseY, nominalHeight) - height / 2, width, height);
    }

    double workingTopY(
        double bottleBaseY,
        int nominalHeight,
        double visualClearance
    ) {
        return mouthY(bottleBaseY, nominalHeight) - visualClearance;
    }

    /** Bounded frame-by-frame interpolation for a visual mechanism only. */
    static double interpolateWorkingY(double currentY, double targetY) {
        if (Double.isNaN(currentY) || Double.isInfinite(currentY)) {
            return targetY;
        }
        double difference = targetY - currentY;
        if (Math.abs(difference) < 0.25) return targetY;
        double step = difference * INTERPOLATION_FACTOR;
        if (Math.abs(step) > MAX_INTERPOLATION_STEP) {
            step = Math.copySign(MAX_INTERPOLATION_STEP, step);
        }
        return currentY + step;
    }

    private int scaled(int nominalValue) {
        return Math.max(1, (int)Math.round(nominalValue * visualScale));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
