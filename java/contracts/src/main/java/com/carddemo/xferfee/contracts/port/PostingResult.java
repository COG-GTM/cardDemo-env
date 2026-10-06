package com.carddemo.xferfee.contracts.port;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import java.util.List;

/**
 * Output of {@link AccountPosting}: XFER.FEES / XFER_FEE_LEDGER rows, the rewritten
 * account master (ACCTDATA.XFER, in input order) and STEP020's report.
 */
public record PostingResult(
        List<TransferPosted> posted,
        List<TransferRejected> rejected,
        List<Account> accounts,
        StepReport report) {

    public PostingResult {
        posted = List.copyOf(posted);
        rejected = List.copyOf(rejected);
        accounts = List.copyOf(accounts);
    }
}
