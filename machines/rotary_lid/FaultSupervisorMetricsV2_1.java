/** Read-only experiment counters collected by FaultSupervisorCD. */
public final class FaultSupervisorMetricsV2_1 {
    public final int validEvents;
    public final int rejectedMessages;
    public final int duplicateMessages;
    public final int automaticAttempts;
    public final int verifiedRecoveries;
    public final int resourceWaits;
    public final int manualEscalations;
    public final int recoveryFailures;
    public final int unsafeActuatorOutputs;

    public FaultSupervisorMetricsV2_1(
        int validEvents,
        int rejectedMessages,
        int duplicateMessages,
        int automaticAttempts,
        int verifiedRecoveries,
        int resourceWaits,
        int manualEscalations,
        int recoveryFailures,
        int unsafeActuatorOutputs
    ) {
        this.validEvents = validEvents;
        this.rejectedMessages = rejectedMessages;
        this.duplicateMessages = duplicateMessages;
        this.automaticAttempts = automaticAttempts;
        this.verifiedRecoveries = verifiedRecoveries;
        this.resourceWaits = resourceWaits;
        this.manualEscalations = manualEscalations;
        this.recoveryFailures = recoveryFailures;
        this.unsafeActuatorOutputs = unsafeActuatorOutputs;
    }

    public String summary() {
        return "events=" + validEvents +
            " rejected=" + rejectedMessages +
            " duplicates=" + duplicateMessages +
            " attempts=" + automaticAttempts +
            " verified=" + verifiedRecoveries +
            " resourceWaits=" + resourceWaits +
            " manual=" + manualEscalations +
            " failures=" + recoveryFailures +
            " unsafeOutputs=" + unsafeActuatorOutputs;
    }
}
