package com.carddemo.xferfee.contracts;

import java.util.List;

/** STEP030 (CBXFR03C): renders the reconciliation report from posted fees. */
public interface Reconciliation {

    ReconResult reconcile(List<TransferPosted> posted);

    /** {@code reportLines} is the exact {@code XFER.RECON.RPT} text, one entry per line, no newline. */
    record ReconResult(
            List<String> reportLines,
            StepReport report) {
    }
}
