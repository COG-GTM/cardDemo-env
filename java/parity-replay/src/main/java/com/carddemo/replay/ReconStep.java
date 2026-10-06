package com.carddemo.replay;

import com.carddemo.observability.ChainStep;
import com.carddemo.observability.LegacyCounter;
import com.carddemo.observability.StepCounters;
import com.carddemo.observability.StepOutcome;
import java.util.List;

/** CBXFR03C: grand-total the posted fees; RC 4 when there is nothing to reconcile. */
final class ReconStep {

    private static final ChainStep STEP = ChainStep.STEP030;

    StepOutcome run(List<FeeRecord> fees) {
        StepCounters counters = new StepCounters(STEP);
        if (fees.isEmpty()) {
            return new StepOutcome(STEP, 4, counters, false,
                    List.of(STEP.program() + ": NO FEE RECORDS"), List.of(), List.of());
        }
        for (FeeRecord fee : fees) {
            counters.add(LegacyCounter.GRAND_TOTAL_FEE, fee.fee());
        }
        return new StepOutcome(STEP, 0, counters, true, List.of(), List.of(), List.of());
    }
}
