package com.carddemo.xferfee.contracts;

import java.util.List;

/** STEP020 (XFERFEE): prices and posts transfers, rewrites the account master, writes the ledger. */
public interface AccountPosting {

    PostingResult post(List<TransferRequested> transfers, List<Account> accountMaster, List<LedgerEntry> ledgerBefore);

    /**
     * {@code accountMasterAfter} is the full new generation in master order (BR-16);
     * {@code ledgerAfter} is the committed ledger. When {@code report.returnCode() > 4} the step
     * abended: nothing is committed ({@code ledgerAfter == ledgerBefore}) and no datasets are kept.
     */
    record PostingResult(
            List<TransferPosted> posted,
            List<Account> accountMasterAfter,
            List<LedgerEntry> ledgerAfter,
            List<TransferRejected> rejected,
            StepReport report) {
    }
}
