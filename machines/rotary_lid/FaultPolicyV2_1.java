/** Immutable recovery rule selected from the IP fault catalogue. */
public final class FaultPolicyV2_1 {
    public enum Disposition {
        AUTOMATIC_RETRY,
        RESOURCE_WAIT,
        MANUAL_RECONCILIATION
    }

    public final String subsystem;
    public final String faultCode;
    public final String requiredSeverity;
    public final Disposition disposition;
    public final String action;
    public final int maxAttempts;
    public final boolean requiresSafeStop;
    public final String safeEvidence;
    public final String serviceEvidence;

    private FaultPolicyV2_1(
        String subsystem,
        String faultCode,
        String requiredSeverity,
        Disposition disposition,
        String action,
        int maxAttempts,
        boolean requiresSafeStop,
        String safeEvidence,
        String serviceEvidence
    ) {
        this.subsystem = subsystem;
        this.faultCode = faultCode;
        this.requiredSeverity = requiredSeverity;
        this.disposition = disposition;
        this.action = action;
        this.maxAttempts = maxAttempts;
        this.requiresSafeStop = requiresSafeStop;
        this.safeEvidence = safeEvidence;
        this.serviceEvidence = serviceEvidence;
    }

    public static FaultPolicyV2_1 select(
        FaultProtocolV2_1.FaultEvent event
    ) {
        FaultPolicyV2_1 policy = lookup(event.subsystem, event.faultCode);
        if (policy == null) {
            throw new IllegalArgumentException(
                "unsupported fault " + event.subsystem + "/" +
                event.faultCode
            );
        }
        if (!policy.requiredSeverity.equals(event.severity)) {
            throw new IllegalArgumentException(
                "severity must be " + policy.requiredSeverity + " for " +
                event.faultCode
            );
        }
        return policy;
    }

    public String summary() {
        return subsystem + "/" + faultCode + " " + disposition.name() +
            " action=" + action + " attempts=" + maxAttempts +
            " safeStop=" + requiresSafeStop;
    }

    private static FaultPolicyV2_1 lookup(
        String subsystem,
        String faultCode
    ) {
        if ("ROTARY".equals(subsystem)) {
            if ("MOTOR_STALL".equals(faultCode)) {
                return manual(subsystem, faultCode, "CRITICAL",
                    "motor_off+bottle_positions_reconciled",
                    "independent_position_confirmed");
            }
            if ("ALIGNMENT_TIMEOUT".equals(faultCode)) {
                return manual(subsystem, faultCode, "WARNING",
                    "motor_off+bottle_positions_reconciled",
                    "independent_position_confirmed");
            }
            if ("POSITION_SENSOR_FAILURE".equals(faultCode)) {
                return manual(subsystem, faultCode, "CRITICAL",
                    "motor_off+bottle_positions_reconciled",
                    "independent_position_confirmed");
            }
        }
        if ("LID".equals(subsystem)) {
            if ("MAGAZINE_EMPTY".equals(faultCode)) {
                return new FaultPolicyV2_1(
                    subsystem, faultCode, "RESOURCE",
                    Disposition.RESOURCE_WAIT, "WAIT_RESOURCE", 0, false,
                    "actuators_off", "lid_available"
                );
            }
            if ("PICK_TIMEOUT".equals(faultCode)) {
                return automatic(subsystem, faultCode, "WARNING",
                    "RETRY_PICK", "actuator_home+no_lid_held",
                    "lid_picked");
            }
            if ("PLACEMENT_TIMEOUT".equals(faultCode)) {
                return manual(subsystem, faultCode, "CRITICAL",
                    "actuators_off+placement_reconciled",
                    "lid_state_confirmed");
            }
            if ("LID_SENSOR_FAULT".equals(faultCode)) {
                return manual(subsystem, faultCode, "CRITICAL",
                    "actuators_off+placement_reconciled",
                    "lid_state_confirmed");
            }
        }
        if ("TRANSFER".equals(subsystem)) {
            if ("ARRIVAL_TIMEOUT".equals(faultCode)) {
                return automatic(subsystem, faultCode, "WARNING",
                    "RETRY_TRANSFER", "motor_off+occupancy_consistent",
                    "arrival_confirmed");
            }
            if ("DEPARTURE_TIMEOUT".equals(faultCode) ||
                "PHOTO_EYE_FAILURE".equals(faultCode) ||
                "POSITION_CONFLICT".equals(faultCode)) {
                return manual(subsystem, faultCode, "CRITICAL",
                    "motor_off+occupancy_consistent",
                    "location_confirmed");
            }
        }
        return null;
    }

    private static FaultPolicyV2_1 automatic(
        String subsystem,
        String faultCode,
        String severity,
        String action,
        String safeEvidence,
        String serviceEvidence
    ) {
        return new FaultPolicyV2_1(
            subsystem, faultCode, severity, Disposition.AUTOMATIC_RETRY,
            action, 1, false, safeEvidence, serviceEvidence
        );
    }

    private static FaultPolicyV2_1 manual(
        String subsystem,
        String faultCode,
        String severity,
        String safeEvidence,
        String serviceEvidence
    ) {
        return new FaultPolicyV2_1(
            subsystem, faultCode, severity,
            Disposition.MANUAL_RECONCILIATION,
            "NO_AUTOMATIC_ACTION", 0, true, safeEvidence, serviceEvidence
        );
    }
}
