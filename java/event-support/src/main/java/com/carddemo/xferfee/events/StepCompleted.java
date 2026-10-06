package com.carddemo.xferfee.events;

import com.carddemo.xferfee.contracts.StepReport;
import java.util.List;

/**
 * End-of-step marker that follows the step's data events on the same topic and partition. Carries
 * every step report of the run so far, so the next step can apply its JCL COND test.
 */
public record StepCompleted(List<StepReport> steps) {

    public StepReport last() {
        return steps.get(steps.size() - 1);
    }
}
