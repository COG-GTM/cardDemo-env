package com.carddemo.observability;

import java.util.Map;

/** Work parked by an RC 8 abend so it can be replayed once the cause is fixed. */
public record DeadLetterEntry(
        String step,
        String program,
        int returnCode,
        String reason,
        String tranId,
        Map<String, String> payload) {
}
