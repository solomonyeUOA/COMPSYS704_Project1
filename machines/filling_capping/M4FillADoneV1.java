/** Correlated Filler A completion accepted by Filler B. */
public final class M4FillADoneV1 {
    private final M4BottleContextV1 context;
    private final int measuredAMl;

    public M4FillADoneV1(M4BottleContextV1 context, int measuredAMl) {
        if (context == null || measuredAMl < 0 ||
            measuredAMl > context.getCapacityMl()) {
            throw new IllegalArgumentException("invalid measured A volume");
        }
        this.context = context;
        this.measuredAMl = measuredAMl;
    }

    public static M4FillADoneV1 parse(String payload) {
        String[] fields = M4ProtocolV1.fields(payload, 6);
        M4BottleContextV1 context = M4BottleContextV1.parse(
            fields[0] + "|" + fields[1] + "|" + fields[2] + "|" +
            fields[3] + "|" + fields[4]
        );
        return new M4FillADoneV1(
            context,
            M4ProtocolV1.unsignedInteger(fields[5], "measuredAMl")
        );
    }

    public M4BottleContextV1 getContext() {
        return context;
    }

    public int getMeasuredAMl() {
        return measuredAMl;
    }

    public String encode() {
        return context.encode() + "|" + measuredAMl;
    }
}
