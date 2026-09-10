/**
 * Parser and validated data holder for the POS ORDER V2 protocol.
 *
 * Format:
 * orderId|productCount|productId,sizeCode,A%,B%,quantity;...
 *
 * OrderV1 remains frozen and is parsed separately for backward compatibility.
 */
public final class OrderV2 {
    public static final int MAX_PRODUCTS = OrderV1.MAX_PRODUCTS;
    public static final String SMALL = "S";
    public static final String LARGE = "L";
    public static final int SMALL_CAPACITY_ML = 200;
    public static final int LARGE_CAPACITY_ML = 500;

    public final String orderId;
    public final int productCount;
    public final String[] productIds;
    public final String[] sizeCodes;
    public final int[] capacitiesMl;
    public final int[] liquidARatios;
    public final int[] liquidBRatios;
    public final int[] quantities;

    private OrderV2(
        String parsedOrderId,
        int parsedProductCount,
        String[] parsedProductIds,
        String[] parsedSizeCodes,
        int[] parsedCapacitiesMl,
        int[] parsedLiquidARatios,
        int[] parsedLiquidBRatios,
        int[] parsedQuantities
    ) {
        orderId = parsedOrderId;
        productCount = parsedProductCount;
        productIds = parsedProductIds;
        sizeCodes = parsedSizeCodes;
        capacitiesMl = parsedCapacitiesMl;
        liquidARatios = parsedLiquidARatios;
        liquidBRatios = parsedLiquidBRatios;
        quantities = parsedQuantities;
    }

    /** Returns a validated V2 order, or null when the payload is invalid. */
    public static OrderV2 parse(String payload) {
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
        String[] sizeCodes = new String[MAX_PRODUCTS];
        int[] capacitiesMl = new int[MAX_PRODUCTS];
        int[] liquidARatios = new int[MAX_PRODUCTS];
        int[] liquidBRatios = new int[MAX_PRODUCTS];
        int[] quantities = new int[MAX_PRODUCTS];

        for (int index = 0; index < productCount; index++) {
            String[] fields = encodedProducts[index].split(",", -1);
            if (fields.length != 5 || fields[0].isEmpty()) {
                return null;
            }
            int capacity = capacityFor(fields[1]);
            if (capacity == 0) {
                return null;
            }

            try {
                productIds[index] = fields[0];
                sizeCodes[index] = fields[1];
                capacitiesMl[index] = capacity;
                liquidARatios[index] = Integer.parseInt(fields[2]);
                liquidBRatios[index] = Integer.parseInt(fields[3]);
                quantities[index] = Integer.parseInt(fields[4]);
            }
            catch (NumberFormatException error) {
                return null;
            }

            if (liquidARatios[index] < 0 ||
                liquidARatios[index] > 100 ||
                liquidBRatios[index] < 0 ||
                liquidBRatios[index] > 100 ||
                liquidARatios[index] + liquidBRatios[index] != 100 ||
                quantities[index] <= 0) {
                return null;
            }
        }

        return new OrderV2(
            orderFields[0],
            productCount,
            productIds,
            sizeCodes,
            capacitiesMl,
            liquidARatios,
            liquidBRatios,
            quantities
        );
    }

    public String productIdAt(int index) {
        checkIndex(index);
        return productIds[index];
    }

    public String sizeCodeAt(int index) {
        checkIndex(index);
        return sizeCodes[index];
    }

    public int capacityMlAt(int index) {
        checkIndex(index);
        return capacitiesMl[index];
    }

    public int liquidARatioAt(int index) {
        checkIndex(index);
        return liquidARatios[index];
    }

    public int liquidBRatioAt(int index) {
        checkIndex(index);
        return liquidBRatios[index];
    }

    public int quantityAt(int index) {
        checkIndex(index);
        return quantities[index];
    }

    public static int capacityFor(String sizeCode) {
        if (SMALL.equals(sizeCode)) {
            return SMALL_CAPACITY_ML;
        }
        if (LARGE.equals(sizeCode)) {
            return LARGE_CAPACITY_ML;
        }
        return 0;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= productCount) {
            throw new IndexOutOfBoundsException("product index " + index);
        }
    }
}
