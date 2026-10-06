package com.carddemo.xferfee.observability;

import com.carddemo.xferfee.contracts.TransferRejected;
import java.util.List;

/**
 * What operators see for one step: its return code and severity, the SYSOUT counters as values,
 * the rejected transfers and the step's SYSOUT lines. A skipped step (JCL {@code COND}) has
 * {@code executed == false}.
 */
public record ObservedStep(
        ChainStep step,
        String program,
        boolean executed,
        int returnCode,
        Severity severity,
        List<CounterValue> counters,
        List<TransferRejected> rejected,
        List<String> sysout) {
}
