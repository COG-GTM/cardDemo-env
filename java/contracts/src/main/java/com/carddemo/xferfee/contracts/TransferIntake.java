package com.carddemo.xferfee.contracts;

import java.util.List;

/** STEP010 (CBXFR01C): selects transfers from the daily file. */
public interface TransferIntake {

    IntakeResult extract(List<DailyTransaction> dailyTransactions, List<CardXref> cardXrefs, List<Account> accounts);

    /** {@code requested} in input order; {@code report.step()} is {@code STEP010}. */
    record IntakeResult(
            List<TransferRequested> requested,
            List<TransferRejected> rejected,
            StepReport report) {
    }
}
