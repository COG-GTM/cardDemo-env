package com.carddemo.xferfee.parity.interim;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.RejectReason;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** XFERFEE posting (BR-06, BR-07, BR-11..BR-17). Replaced by account-posting-service (COG-1238). */
class InterimAccountPosting implements AccountPosting {

    private final FeeSchedule schedule;
    private final FeePolicy policy;

    InterimAccountPosting(FeeSchedule schedule, FeePolicy policy) {
        this.schedule = schedule;
        this.policy = policy;
    }

    @Override
    public PostingResult post(List<TransferRequested> transfers, List<Account> accountMaster, List<LedgerEntry> ledgerBefore) {
        List<Account> master = new ArrayList<>(Cobol.table(accountMaster));
        List<LedgerEntry> ledger = new ArrayList<>(ledgerBefore);
        List<TransferPosted> posted = new ArrayList<>();
        List<String> sysout = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (TransferRequested t : transfers) {
            Optional<FeeRule> found = schedule.effectiveRule(t.bookId(), t.tranDate());
            if (found.isEmpty()) {
                sysout.add("XFERFEE: NO FEE RULE FOR BOOK " + Cobol.pic(t.bookId(), 10));
                return abend(posted, accountMaster, ledgerBefore, sysout, t, RejectReason.NO_FEE_RULE);
            }
            FeeRule rule = found.get();
            FeeResult fee = policy.apply(t.amount(), rule);
            int src = lastIndexOf(master, t.sourceAccountId());
            int tgt = lastIndexOf(master, t.targetAccountId());
            if (src < 0 || tgt < 0) {
                sysout.add("XFERFEE: ACCOUNT NOT FOUND " + Cobol.unsigned(t.sourceAccountId(), 11)
                        + " / " + Cobol.unsigned(t.targetAccountId(), 11));
                return abend(posted, accountMaster, ledgerBefore, sysout, t, RejectReason.UNKNOWN_ACCOUNT);
            }
            if (ledger.stream().anyMatch(e -> e.tranId().equals(t.tranId()))) {
                sysout.add("XFERFEE: LEDGER INSERT FAILED (duplicate TRAN_ID)");
                return abend(posted, accountMaster, ledgerBefore, sysout, t, RejectReason.DUPLICATE_TRAN_ID);
            }
            // same statement order as 2100-POST-ONE, so src == tgt behaves identically
            Account s = master.get(src);
            master.set(src, with(s, s.currentBalance().subtract(t.amount()).subtract(fee.feeAmount()),
                    s.currentCycleCredit(), s.currentCycleDebit()));
            Account g = master.get(tgt);
            master.set(tgt, with(g, g.currentBalance().add(t.amount()),
                    g.currentCycleCredit().add(t.amount()), g.currentCycleDebit()));
            s = master.get(src);
            master.set(src, with(s, s.currentBalance(), s.currentCycleCredit(),
                    s.currentCycleDebit().add(t.amount()).add(fee.feeAmount())));
            posted.add(new TransferPosted(t.tranId(), t.tranDate(), t.sourceAccountId(), t.targetAccountId(), t.bookId(),
                    t.amount(), rule.feePct(), fee.feeAmount(), fee.capApplied(), rule.effectiveDate()));
            ledger.add(new LedgerEntry(t.tranId(), t.tranDate(), t.sourceAccountId(), t.targetAccountId(), t.bookId(),
                    t.amount(), fee.feeAmount(), fee.capApplied()));
            total = total.add(fee.feeAmount());
        }
        sysout.add("XFERFEE: TRANSFERS POSTED " + Cobol.unsigned(posted.size(), 9));
        sysout.add("XFERFEE: TOTAL FEES " + Cobol.signed(total));
        return new PostingResult(posted, master, ledger, List.of(), new StepReport("STEP020", 0, sysout));
    }

    private static PostingResult abend(List<TransferPosted> posted, List<Account> master, List<LedgerEntry> ledgerBefore,
            List<String> sysout, TransferRequested t, RejectReason reason) {
        sysout.add("XFERFEE: 9999-ABEND-PROGRAM");
        return new PostingResult(posted, master, ledgerBefore,
                List.of(new TransferRejected(t.tranId(), t.cardNumber(), reason, sysout.get(sysout.size() - 2))),
                new StepReport("STEP020", 8, sysout));
    }

    /** 2200-FIND-ACCOUNTS scans the whole table, so the last entry with the id wins. */
    private static int lastIndexOf(List<Account> master, long accountId) {
        for (int i = master.size() - 1; i >= 0; i--) {
            if (master.get(i).accountId() == accountId) {
                return i;
            }
        }
        return -1;
    }

    private static Account with(Account a, BigDecimal balance, BigDecimal cycleCredit, BigDecimal cycleDebit) {
        return new Account(a.accountId(), a.activeStatus(), balance, a.creditLimit(), a.cashCreditLimit(), a.openDate(),
                a.expirationDate(), a.reissueDate(), cycleCredit, cycleDebit, a.addressZip(), a.groupId());
    }
}
