package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.legacy.ingress.IngressBatch;
import java.util.List;

/**
 * One case's inputs as contract records. {@code batch} is set only for {@code --codec=java}: it
 * carries the original record images the legacy-adapter egress needs.
 */
record ReplayInputs(
        List<DailyTransaction> transactions,
        List<CardXref> xrefs,
        List<Account> accounts,
        List<FeeRule> rules,
        List<LedgerEntry> ledgerBefore,
        IngressBatch batch) {
}
