/** Digital Twin traceability, concurrency, idempotency and non-actuation tests. */
public final class Member2DigitalTwinSelfTest {
    public static void main(String[] args) {
        DigitalTwinStoreV1 store = new DigitalTwinStoreV1();
        check(store.acceptBatchContext("V1|PO1|P1|60|40|2|MIXED"),
            "batch context");

        long time = 1000L;
        String created = workpiece(
            "E1", "B401", "CREATED", "LOADER-1",
            "S,200,GEOM_S,PACK_S", time++
        );
        check(store.applyWorkpieceUpdate(created), "create workpiece");
        check(store.applyWorkpieceUpdate(created), "idempotent duplicate");
        check(!store.applyWorkpieceUpdate(workpiece(
            "E1", "B401", "LOADED", "LOADER-1", "-", time++
        )), "conflicting duplicate event ID");
        check(!store.applyWorkpieceUpdate(workpiece(
            "E2", "B401", "P6", "LABELLER-1", "-", time++
        )), "illegal jump does not create false P6 state");

        String[] route = {
            "LOADED", "P1", "FILLED", "LIDDED", "CAPPED", "P6",
            "LABELLED", "UNLOADED", "SORTED", "COMPLETE"
        };
        for (int index = 0; index < route.length; index++) {
            check(store.applyWorkpieceUpdate(workpiece(
                "E" + (index + 3), "B401", route[index],
                resourceFor(route[index]), "-", time++
            )), "accept route stage " + route[index]);
        }
        WorkpieceTwin.Snapshot first = store.workpieceSnapshot("B401");
        check(first != null && first.stage == WorkpieceTwin.Stage.COMPLETE,
            "first bottle complete");
        check(first.history.size() == 11,
            "one history entry per accepted transition");
        boolean immutable = false;
        try {
            first.history.add("illegal mutation");
        }
        catch (UnsupportedOperationException exception) {
            immutable = true;
        }
        check(immutable, "snapshot history is immutable");

        check(store.applyWorkpieceUpdate(workpiece(
            "B2-E1", "B402", "CREATED", "LOADER-1",
            "L,500,GEOM_L,PACK_L", time++
        )), "create concurrent second bottle");
        check(store.applyWorkpieceUpdate(workpiece(
            "B2-E2", "B402", "LOADED", "LOADER-1", "-", time++
        )), "advance second bottle independently");
        check(store.workpieceSnapshot("B401").stage ==
            WorkpieceTwin.Stage.COMPLETE, "second bottle does not alter first");

        String resource = M2TwinUpdateV1.resource(
            "R1", "CONVEYOR-1", "CONVEYOR", "B402",
            M2StatusV1.BUSY, "MOVE_TO_P1", "-", time++
        );
        check(store.applyResourceUpdate(resource), "resource update");
        check(store.applyResourceUpdate(resource), "resource duplicate");
        ResourceTwin.Snapshot resourceSnapshot =
            store.resourceSnapshot("CONVEYOR-1");
        check(resourceSnapshot != null &&
            "B402".equals(resourceSnapshot.linkedWorkpieceId),
            "resource/workpiece correlation");
        check(!store.applyResourceUpdate(M2TwinUpdateV1.resource(
            "R2", "CONVEYOR-1", "CONVEYOR", "B401",
            M2StatusV1.DONE, "STALE", "-", 1L
        )), "stale resource update rejected");

        String all = store.snapshot("*");
        check(all.contains("W=2") && all.contains("R=1"),
            "combined read-only snapshot");
        check(store.snapshot("W:B401").contains("|COMPLETE|"),
            "selected workpiece query");
        check(store.getRejectedUpdateCount() >= 3,
            "rejected updates are observable");

        System.out.println("Member2DigitalTwinSelfTest PASSED");
    }

    private static String workpiece(
        String eventId,
        String bottleId,
        String eventType,
        String resource,
        String details,
        long time
    ) {
        return M2TwinUpdateV1.workpiece(
            eventId, bottleId, eventType, resource, details, time
        );
    }

    private static String resourceFor(String eventType) {
        if ("LOADED".equals(eventType)) {
            return "LOADER-1";
        }
        if ("P1".equals(eventType)) {
            return "ROTARY-P1";
        }
        if ("FILLED".equals(eventType)) {
            return "FILLER-AB";
        }
        if ("LIDDED".equals(eventType)) {
            return "LID-1";
        }
        if ("CAPPED".equals(eventType)) {
            return "CAPPER-1";
        }
        if ("P6".equals(eventType) || "LABELLED".equals(eventType)) {
            return "LABELLER-1";
        }
        if ("UNLOADED".equals(eventType)) {
            return "UNLOADER-1";
        }
        if ("SORTED".equals(eventType)) {
            return "SORTPACK-1";
        }
        return "COLLECTION";
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
