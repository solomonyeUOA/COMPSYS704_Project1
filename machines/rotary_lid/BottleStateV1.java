/** Mutable workpiece state stored in one rotary-table position. */
public final class BottleStateV1 {
    private final String id;
    private boolean filled;
    private boolean lidPlaced;
    private boolean capped;

    public BottleStateV1(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("bottle id must not be empty");
        }
        this.id = id.trim();
    }

    public String getId() {
        return id;
    }

    public boolean isFilled() {
        return filled;
    }

    public boolean hasLid() {
        return lidPlaced;
    }

    public boolean isCapped() {
        return capped;
    }

    public void markFilled() {
        filled = true;
    }

    public void markLidPlaced() {
        if (!filled) {
            throw new IllegalStateException("cannot place lid before filling");
        }
        lidPlaced = true;
    }

    public void markCapped() {
        if (!lidPlaced) {
            throw new IllegalStateException("cannot cap bottle without lid");
        }
        capped = true;
    }

    public String shortState() {
        return id + "[" +
            (filled ? "F" : "-") +
            (lidPlaced ? "L" : "-") +
            (capped ? "C" : "-") + "]";
    }
}
