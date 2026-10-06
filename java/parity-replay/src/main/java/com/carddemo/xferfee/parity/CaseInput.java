package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import java.nio.file.Path;
import java.util.List;

/** A fixture decoded by {@code java_candidate.py decode}: input datasets plus {@code db2_before}. */
record CaseInput(
        List<DailyTransaction> dailyTransactions,
        List<CardXref> cardXrefs,
        List<Account> accounts,
        List<FeeRule> feeRules,
        List<LedgerEntry> ledgerBefore) {

    static CaseInput load(Path dir) {
        return new CaseInput(
                JsonLines.read(dir.resolve("daily_transactions.jsonl"), DailyTransaction.class),
                JsonLines.read(dir.resolve("card_xref.jsonl"), CardXref.class),
                JsonLines.read(dir.resolve("accounts.jsonl"), Account.class),
                JsonLines.read(dir.resolve("fee_rules.jsonl"), FeeRule.class),
                JsonLines.read(dir.resolve("ledger_before.jsonl"), LedgerEntry.class));
    }
}
