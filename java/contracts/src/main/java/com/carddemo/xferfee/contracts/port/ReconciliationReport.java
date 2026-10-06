package com.carddemo.xferfee.contracts.port;

import java.util.List;

/** Output of {@link Reconciliation}: XFER.RECON.RPT lines and STEP030's report. */
public record ReconciliationReport(List<String> lines, StepReport report) {

    public ReconciliationReport {
        lines = List.copyOf(lines);
    }
}
