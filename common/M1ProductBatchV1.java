import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Coordinator-owned identity and quantity contract for one product batch. */
public final class M1ProductBatchV1 {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final String ENCODED_ORDER_PREFIX = "OID~";

    private final String batchId;
    private final int quantity;
    private final String sizeCode;

    private M1ProductBatchV1(
        String requestedBatchId,
        int requestedQuantity,
        String requestedSizeCode
    ) {
        validateTransportId(requestedBatchId, "batchId");
        if (requestedQuantity < 1) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (OrderV2.capacityFor(requestedSizeCode) == 0) {
            throw new IllegalArgumentException("sizeCode must be S or L");
        }
        batchId = requestedBatchId;
        quantity = requestedQuantity;
        sizeCode = requestedSizeCode;
    }

    /** Builds a stable transport-safe identity without changing safe order IDs. */
    public static M1ProductBatchV1 forProduct(
        String orderId,
        int oneBasedProductIndex,
        int quantity,
        String sizeCode
    ) {
        if (orderId == null || orderId.length() == 0) {
            throw new IllegalArgumentException("orderId is required");
        }
        if (oneBasedProductIndex < 1 || oneBasedProductIndex > 99) {
            throw new IllegalArgumentException("product index must be 1..99");
        }
        String orderComponent = isPlainTransportId(orderId) &&
            !orderId.startsWith(ENCODED_ORDER_PREFIX) ?
            orderId : encodeOrderId(orderId);
        String id = orderComponent + "-P" + String.format(
            Locale.ROOT,
            "%02d",
            Integer.valueOf(oneBasedProductIndex)
        );
        return new M1ProductBatchV1(id, quantity, sizeCode);
    }

    public String getBatchId() {
        return batchId;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getSizeCode() {
        return sizeCode;
    }

    public String encode() {
        return batchId + "|" + quantity + "|" + sizeCode;
    }

    static void validateTransportId(String value, String fieldName) {
        if (!isPlainTransportId(value)) {
            throw new IllegalArgumentException(
                fieldName + " must be printable ASCII without spaces or pipes"
            );
        }
    }

    private static boolean isPlainTransportId(String value) {
        if (value == null || value.length() == 0 ||
            !value.equals(value.trim()) || value.indexOf('|') >= 0) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static String encodeOrderId(String orderId) {
        byte[] bytes = orderId.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(
            ENCODED_ORDER_PREFIX.length() + bytes.length * 2
        );
        encoded.append(ENCODED_ORDER_PREFIX);
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            encoded.append(HEX[unsigned >>> 4]);
            encoded.append(HEX[unsigned & 0x0f]);
        }
        return encoded.toString();
    }
}
