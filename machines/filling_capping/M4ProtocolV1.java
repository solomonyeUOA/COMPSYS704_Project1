/** Strict helpers for the ASCII pipe-delimited M4 protocol. */
public final class M4ProtocolV1 {
    private M4ProtocolV1() {
    }

    public static String[] fields(String payload, int expectedCount) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != expectedCount) {
            throw new IllegalArgumentException(
                "expected " + expectedCount + " fields"
            );
        }
        return fields;
    }

    public static int unsignedInteger(String value, String fieldName) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException(
                fieldName + " must be a canonical unsigned integer"
            );
        }
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " is too large");
        }
    }

    public static void validateBottleId(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim()) ||
            value.indexOf('|') >= 0 || value.indexOf('\r') >= 0 ||
            value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid bottleId");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < 0x21 || character > 0x7e) {
                throw new IllegalArgumentException(
                    "bottleId must be printable ASCII without spaces"
                );
            }
        }
    }

    public static void validateToken(String value, String fieldName) {
        if (value == null || value.isEmpty() ||
            !value.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("invalid " + fieldName);
        }
    }
}
