package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.LedgerEntry;
import java.util.List;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Loads the account master, card xref and ledger, and produces the full rewritten master (BR-16). */
public class AccountMasterService {

    private final AccountRepository accounts;
    private final CardXrefRepository xrefs;
    private final FeeLedgerRepository ledger;
    private final TransactionTemplate tx;

    public AccountMasterService(AccountRepository accounts, CardXrefRepository xrefs,
            FeeLedgerRepository ledger, PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.xrefs = xrefs;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public void load(List<Account> master, List<CardXref> cardXrefs, List<LedgerEntry> ledgerRows) {
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < master.size(); i++) {
                accounts.insert(master.get(i), i + 1);
            }
            cardXrefs.forEach(xrefs::insert);
            ledgerRows.forEach(ledger::insert);
        });
    }

    /** Every account, in the order it was loaded; accounts with no transfers are unchanged. */
    public List<Account> rewrittenMaster() {
        return accounts.findAllInLoadOrder();
    }

    public List<LedgerEntry> ledger() {
        return ledger.findAll();
    }
}
