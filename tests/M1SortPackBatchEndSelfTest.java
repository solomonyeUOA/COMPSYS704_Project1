/** M1 product-boundary publication and queueing regressions. */
public final class M1SortPackBatchEndSelfTest {
    private M1SortPackBatchEndSelfTest() {
    }

    public static void main(String[] args) {
        CoordinatorStateV1.resetForTest();
        require(CoordinatorStateV1.accept(
            "PACK-ORDER|2|P1,S,60,40,1;P2,L,25,75,3"
        ), "multi-product order accepted");

        require(CoordinatorStateV1.recordBottleDone(),
            "first product reaches its declared quantity");
        require(CoordinatorStateV1.finishCurrentSortPackBatch(),
            "first product batch end queued");
        require(CoordinatorStateV1.finishCurrentSortPackBatch(),
            "duplicate first product completion is idempotent");
        CoordinatorStateV1.advanceToNextProduct();
        require(!CoordinatorStateV1.recordBottleDone() &&
            !CoordinatorStateV1.recordBottleDone() &&
            CoordinatorStateV1.recordBottleDone(),
            "second product reaches its declared quantity");
        require(CoordinatorStateV1.finishCurrentSortPackBatch(),
            "second product batch end queues behind the first");

        require("PACK-ORDER-P01|1|S".equals(
            CoordinatorStateV1.nextSortPackBatchEnd(0L)
        ), "first batch end is published before the next product");
        require("PACK-ORDER-P01|1|S".equals(
            CoordinatorStateV1.nextSortPackBatchEnd(199L)
        ), "first batch end remains PRESENT for its hold window");
        require(CoordinatorStateV1.nextSortPackBatchEnd(200L) == null,
            "batch-end copies have an ABSENT retry gap");
        require("PACK-ORDER-P01|1|S".equals(
            CoordinatorStateV1.nextSortPackBatchEnd(800L)
        ), "first batch end retry preserves its payload");
        require(CoordinatorStateV1.nextSortPackBatchEnd(1000L) == null,
            "second bounded copy ends before its retry gap");
        require("PACK-ORDER-P01|1|S".equals(
            CoordinatorStateV1.nextSortPackBatchEnd(1600L)
        ), "first batch gets exactly its final bounded copy");
        require("PACK-ORDER-P02|3|L".equals(
            CoordinatorStateV1.nextSortPackBatchEnd(1800L)
        ), "queued second product cannot replace the first batch end");

        CoordinatorStateV1.resetForTest();
        require(CoordinatorStateV1.nextSortPackBatchEnd(10000L) == null,
            "system reset clears unsent batch boundaries");
        System.out.println("M1SortPackBatchEndSelfTest PASSED");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
