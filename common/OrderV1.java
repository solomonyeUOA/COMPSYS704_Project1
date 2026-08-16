/**
 * Parser and validated data holder for the frozen POS ORDER V1 protocol.
 *
 * This is handwritten support code, not SystemJ compiler-generated Java.
 */
public final class OrderV1 {
    public static final int MAX_PRODUCTS = 4;

    public final String orderId;
    public final int productCount;
    public final String[] productIds;
    public final int[] liquidARatios;
    public final int[] liquidBRatios;
    public final int[] quantities;

    private OrderV1(
        String orderId,
        int productCount,
        String[] productIds,
        int[] liquidARatios,
        int[] liquidBRatios,
        int[] quantities
    ) {
        this.orderId = orderId;
        this.productCount = productCount;
        this.productIds = productIds;
        this.liquidARatios = liquidARatios;
        this.liquidBRatios = liquidBRatios;
        this.quantities = quantities;
    }

    /** Returns a validated order, or null when the payload is invalid. */
    public static OrderV1 parse(String payload) {
        if (payload == null) {
            return null;
        }

        String[] orderFields = payload.split("\\|", -1);
        if (orderFields.length != 3 || orderFields[0].isEmpty()) {
            return null;
        }

        final int productCount;
        try {
            productCount = Integer.parseInt(orderFields[1]);
        }
        catch (NumberFormatException error) {
            return null;
        }

        if (productCount < 1 || productCount > MAX_PRODUCTS) {
            return null;
        }

        String[] encodedProducts = orderFields[2].split(";", -1);
        if (encodedProducts.length != productCount) {
            return null;
        }

        String[] productIds = new String[MAX_PRODUCTS];
        int[] liquidARatios = new int[MAX_PRODUCTS];
        int[] liquidBRatios = new int[MAX_PRODUCTS];
        int[] quantities = new int[MAX_PRODUCTS];

        for (int i = 0; i < productCount; i++) {
            String[] productFields = encodedProducts[i].split(",", -1);
            if (productFields.length != 4 || productFields[0].isEmpty()) {
                return null;
            }

            try {
                productIds[i] = productFields[0];
                liquidARatios[i] = Integer.parseInt(productFields[1]);
                liquidBRatios[i] = Integer.parseInt(productFields[2]);
                quantities[i] = Integer.parseInt(productFields[3]);
            }
            catch (NumberFormatException error) {
                return null;
            }

            if (liquidARatios[i] < 0 || liquidARatios[i] > 100 ||
                liquidBRatios[i] < 0 || liquidBRatios[i] > 100 ||
                liquidARatios[i] + liquidBRatios[i] != 100 ||
                quantities[i] <= 0) {
                return null;
            }
        }

        return new OrderV1(
            orderFields[0],
            productCount,
            productIds,
            liquidARatios,
            liquidBRatios,
            quantities
        );
    }
}
