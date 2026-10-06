package com.carddemo.xferfee.contracts.port;

import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.util.List;

/** Output of {@link TransferIntake}: the XFER.EXTRACT records plus STEP010's report. */
public record IntakeResult(
        long recordsRead,
        List<TransferRequested> selected,
        List<TransferRejected> rejected,
        StepReport report) {

    public IntakeResult {
        selected = List.copyOf(selected);
        rejected = List.copyOf(rejected);
    }
}
