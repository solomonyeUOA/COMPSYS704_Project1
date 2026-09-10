/** Shared state owned by DigitalTwinCD and exposed through immutable strings. */
public final class DigitalTwinStateV1 {
    private static DigitalTwinStoreV1 store = new DigitalTwinStoreV1();
    private static String pendingSnapshot;

    private DigitalTwinStateV1() {
    }

    public static synchronized void reset() {
        store = new DigitalTwinStoreV1();
        pendingSnapshot = null;
    }

    public static synchronized boolean acceptBatchContext(String payload) {
        return store.acceptBatchContext(payload);
    }

    public static synchronized boolean acceptWorkpieceUpdate(String payload) {
        return store.applyWorkpieceUpdate(payload);
    }

    public static synchronized boolean acceptResourceUpdate(String payload) {
        return store.applyResourceUpdate(payload);
    }

    public static synchronized void requestSnapshot(String request) {
        pendingSnapshot = store.snapshot(request);
    }

    public static synchronized String takeSnapshot() {
        String result = pendingSnapshot;
        pendingSnapshot = null;
        return result;
    }

    public static synchronized int getWorkpieceCount() {
        return store.getWorkpieceCount();
    }

    public static synchronized int getResourceCount() {
        return store.getResourceCount();
    }
}
