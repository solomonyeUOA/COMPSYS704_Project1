/**
 * Fixed-point recipe ratio support shared by POS, Coordinator and M4.
 *
 * One unit is one tenth of one percent: 0 = 0.0%, 1000 = 100.0%.
 * ORDER payloads stay human-readable (for example 33.3), while the frozen
 * SystemJ Integer recipe signals carry the fixed-point unit value (333).
 */
public final class RecipeRatioV2 {
    public static final int UNITS_PER_PERCENT = 10;
    public static final int TOTAL_UNITS = 100 * UNITS_PER_PERCENT;

    private RecipeRatioV2() {
    }

    /** Parse a percentage with at most one decimal place. */
    public static int parsePercent(String text) {
        if (text == null) {
            throw new IllegalArgumentException("percentage is required");
        }
        String value = text.trim();
        if (!value.matches("(?:0|[1-9][0-9]?|100)(?:\\.[0-9])?")) {
            throw new IllegalArgumentException(
                "percentage must be from 0.0 to 100.0 with at most one decimal place"
            );
        }
        int decimal = value.indexOf('.');
        int whole = Integer.parseInt(decimal < 0 ? value :
            value.substring(0, decimal));
        int tenth = decimal < 0 ? 0 : value.charAt(decimal + 1) - '0';
        int units = whole * UNITS_PER_PERCENT + tenth;
        if (units > TOTAL_UNITS) {
            throw new IllegalArgumentException(
                "percentage must be from 0.0 to 100.0"
            );
        }
        return units;
    }

    public static boolean isValidUnits(int units) {
        return units >= 0 && units <= TOTAL_UNITS;
    }

    public static String formatPercent(int units) {
        if (!isValidUnits(units)) {
            throw new IllegalArgumentException("ratio units must be 0..1000");
        }
        return (units / UNITS_PER_PERCENT) + "." +
            (units % UNITS_PER_PERCENT);
    }

    /** Convert tenths-of-percent units to the nearest whole millilitre. */
    public static int targetMl(int capacityMl, int units) {
        if (capacityMl <= 0 || !isValidUnits(units)) {
            throw new IllegalArgumentException("invalid capacity or ratio units");
        }
        return (int)(((long)capacityMl * units + TOTAL_UNITS / 2) /
            TOTAL_UNITS);
    }
}
