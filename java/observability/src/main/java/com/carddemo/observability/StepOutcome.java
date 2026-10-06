package com.carddemo.observability;

import com.carddemo.contracts.TransferRejected;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of one chain step. {@code countersDisplayed} is false when the legacy program
 * ends without DISPLAYing its totals (XFERFEE abend, CBXFR03C with no fee records).
 */
public record StepOutcome(
        ChainStep step,
        int returnCode,
        StepCounters counters,
        boolean countersDisplayed,
        List<String> messages,
        List<TransferRejected> rejects,
        List<DeadLetterEntry> deadLetters) {

    public StepOutcome {
        messages = List.copyOf(messages);
        rejects = List.copyOf(rejects);
        deadLetters = List.copyOf(deadLetters);
    }

    public List<String> sysoutLines() {
        if (!countersDisplayed) {
            return messages;
        }
        ArrayList<String> lines = new ArrayList<>(messages);
        counters.asMap().forEach((counter, value) -> lines.add(SysoutFormat.line(counter, value)));
        return lines;
    }
}
