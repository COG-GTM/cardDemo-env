package com.carddemo.xferfee.contracts;

import java.util.List;

/**
 * Operator-visible outcome of one batch step: the JCL step name ({@code STEP010}..{@code STEP030}),
 * its return code, and the SYSOUT lines (without trailing newlines).
 */
public record StepReport(
        String step,
        int returnCode,
        List<String> sysout) {
}
