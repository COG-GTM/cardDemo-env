package com.carddemo.xferfee.observability;

import java.time.Instant;
import java.util.List;

/** Raised when a step ends RC 8 or higher. */
public record ChainAlert(
        String chain,
        ChainStep step,
        String program,
        int returnCode,
        Severity severity,
        String summary,
        List<String> sysout,
        Instant raisedAt) {
}
