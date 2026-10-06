package com.carddemo.xferfee.contracts.port;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.util.List;

/** STEP020 / XFERFEE: compute fees and post transfers to the account master and ledger. */
public interface AccountPosting {

    PostingResult post(List<TransferRequested> transfers, List<Account> accounts);
}
