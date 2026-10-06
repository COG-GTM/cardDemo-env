package com.carddemo.xferfee.contracts.port;

import java.util.List;

/**
 * What a legacy job step reports: its condition code and its SYSOUT lines.
 *
 * @param step       JCL step name (STEP010, STEP020, STEP030)
 * @param returnCode step condition code
 * @param sysout     DISPLAY lines in order, without line terminators
 */
public record StepReport(String step, int returnCode, List<String> sysout) {

    public StepReport {
        sysout = List.copyOf(sysout);
    }
}
