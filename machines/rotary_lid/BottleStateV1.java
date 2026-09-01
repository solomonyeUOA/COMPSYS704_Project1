/** Mutable workpiece state stored in one rotary-table position. */
public final class BottleStateV1 {
    private final String id;
    private BottleContextV1 context;
    private boolean filled;
    private boolean lidPlaced;
    private boolean capped;
    private boolean labelled;

    public BottleStateV1(String id) {
        BottleContextV1.validateBottleId(id);
        this.id = id;
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

    public boolean isLabelled() {
        return labelled;
    }

    public BottleContextV1 getContext() {
        return context;
    }

    public void setContext(BottleContextV1 value) {
        if (value == null || !id.equals(value.getBottleId())) {
            throw new IllegalArgumentException("context bottleId mismatch");
        }
        context = value;
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

    public void markLabelled() {
        if (!capped) {
            throw new IllegalStateException("cannot label bottle before capping");
        }
        labelled = true;
    }

    public String shortState() {
        return id + "[" +
            (filled ? "F" : "-") +
            (lidPlaced ? "L" : "-") +
            (capped ? "C" : "-") +
            (labelled ? "B" : "-") + "]";
    }
}
