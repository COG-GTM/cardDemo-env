package com.carddemo.xferfee.posting;

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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * XFERFEE (STEP020) posting: rule lookup (BR-06/07), fee (BR-08..BR-10 via {@link FeePolicy}),
 * balance and cycle updates with no funds/status/limit checks (BR-11..BR-13), ledger keyed by
 * TRAN_ID (BR-14), single commit at end of step and full rollback on any abend (BR-15), new master
 * generation in master order (BR-16). Any failure abends the whole step with RC 8.
 */
public final class CobolAccountPosting implements AccountPosting {

    public static final String STEP = "STEP020";
    static final int TABLE_LIMIT = 500;
    static final String DUPLICATE_KEY_SQLCODE = "-0000000403";
    static final List<String> ABEND_TRAILER = List.of(
            "XFERFEE: 9999-ABEND-PROGRAM",
            "libcob: warning: implicit CLOSE of XFERFEE ('XFERFEE')",
            "libcob: warning: implicit CLOSE of ACCTOUT ('ACCTOUT')",
            "libcob: warning: implicit CLOSE of XFEREXTR ('XFEREXTR')",
            "libcob: warning: implicit CLOSE of ACCTFILE ('ACCTFILE')");

    private final FeeSchedule feeSchedule;
    private final FeePolicy feePolicy;

    public CobolAccountPosting(FeeSchedule feeSchedule, FeePolicy feePolicy) {
        this.feeSchedule = feeSchedule;
        this.feePolicy = feePolicy;
    }

    @Override
    public PostingResult post(List<TransferRequested> transfers, List<Account> accountMaster,
            List<LedgerEntry> ledgerBefore) {
        List<MutableAccount> master = accountMaster.stream().limit(TABLE_LIMIT).map(MutableAccount::new).toList();
        List<TransferPosted> posted = new ArrayList<>();
        List<LedgerEntry> ledger = new ArrayList<>(ledgerBefore);
        Set<String> ledgerKeys = new HashSet<>();
        ledgerBefore.forEach(entry -> ledgerKeys.add(entry.tranId().stripTrailing()));
        BigDecimal feeTotal = BigDecimal.ZERO.setScale(2);

        for (TransferRequested transfer : transfers) {
            Optional<FeeRule> rule = feeSchedule.effectiveRule(transfer.bookId(), transfer.tranDate());
            if (rule.isEmpty()) {
                return abend(accountMaster, ledgerBefore, transfer, RejectReason.NO_FEE_RULE,
                        "XFERFEE: NO FEE RULE FOR BOOK " + pad(transfer.bookId(), 10));
            }
            FeeResult fee = feePolicy.apply(transfer.amount(), rule.get());
            MutableAccount source = find(master, transfer.sourceAccountId());
            MutableAccount target = find(master, transfer.targetAccountId());
            if (source == null || target == null) {
                return abend(accountMaster, ledgerBefore, transfer, RejectReason.UNKNOWN_ACCOUNT,
                        "XFERFEE: ACCOUNT NOT FOUND %011d / %011d".formatted(
                                transfer.sourceAccountId(), transfer.targetAccountId()));
            }
            source.balance = source.balance.subtract(transfer.amount()).subtract(fee.feeAmount());
            target.balance = target.balance.add(transfer.amount());
            target.cycleCredit = target.cycleCredit.add(transfer.amount());
            source.cycleDebit = source.cycleDebit.add(transfer.amount()).add(fee.feeAmount());
            if (!ledgerKeys.add(transfer.tranId().stripTrailing())) {
                return abend(accountMaster, ledgerBefore, transfer, RejectReason.DUPLICATE_TRAN_ID,
                        "XFERFEE: LEDGER INSERT FAILED " + DUPLICATE_KEY_SQLCODE);
            }
            posted.add(new TransferPosted(transfer.tranId(), transfer.tranDate(), transfer.sourceAccountId(),
                    transfer.targetAccountId(), transfer.bookId(), transfer.amount(), rule.get().feePct(),
                    fee.feeAmount(), fee.capApplied(), rule.get().effectiveDate()));
            ledger.add(new LedgerEntry(transfer.tranId(), transfer.tranDate(), transfer.sourceAccountId(),
                    transfer.targetAccountId(), transfer.bookId(), transfer.amount(), fee.feeAmount(),
                    fee.capApplied()));
            feeTotal = feeTotal.add(fee.feeAmount());
        }
        List<String> sysout = List.of(
                "XFERFEE: TRANSFERS POSTED " + "%09d".formatted(posted.size()),
                "XFERFEE: TOTAL FEES " + signedDisplay(feeTotal, 9, 2));
        return new PostingResult(posted, master.stream().map(MutableAccount::toAccount).toList(), ledger,
                List.of(), new StepReport(STEP, 0, sysout));
    }

    private static PostingResult abend(List<Account> master, List<LedgerEntry> ledgerBefore,
            TransferRequested transfer, RejectReason reason, String message) {
        List<String> sysout = new ArrayList<>();
        sysout.add(message);
        sysout.addAll(ABEND_TRAILER);
        TransferRejected rejected = new TransferRejected(transfer.tranId(), transfer.cardNumber(), reason, message);
        return new PostingResult(List.of(), master, ledgerBefore, List.of(rejected), new StepReport(STEP, 8, sysout));
    }

    /** 2200-FIND-ACCOUNTS scans the whole table, so the last matching entry wins. */
    private static MutableAccount find(List<MutableAccount> master, long accountId) {
        MutableAccount found = null;
        for (MutableAccount account : master) {
            if (account.source.accountId() == accountId) {
                found = account;
            }
        }
        return found;
    }

    /** GnuCOBOL DISPLAY of a signed numeric item: leading sign, all digits, no decimal point. */
    public static String signedDisplay(BigDecimal value, int integerDigits, int scale) {
        String digits = value.abs().movePointRight(scale).toBigInteger().toString();
        String padded = "0".repeat(Math.max(0, integerDigits + scale - digits.length())) + digits;
        return (value.signum() < 0 ? "-" : "+") + padded.substring(padded.length() - (integerDigits + scale));
    }

    static String pad(String value, int length) {
        String text = value == null ? "" : value;
        return text.length() >= length ? text.substring(0, length) : text + " ".repeat(length - text.length());
    }

    private static final class MutableAccount {
        private final Account source;
        private BigDecimal balance;
        private BigDecimal cycleCredit;
        private BigDecimal cycleDebit;

        MutableAccount(Account source) {
            this.source = source;
            this.balance = source.currentBalance();
            this.cycleCredit = source.currentCycleCredit();
            this.cycleDebit = source.currentCycleDebit();
        }

        Account toAccount() {
            return new Account(source.accountId(), source.activeStatus(), balance, source.creditLimit(),
                    source.cashCreditLimit(), source.openDate(), source.expirationDate(), source.reissueDate(),
                    cycleCredit, cycleDebit, source.addressZip(), source.groupId());
        }
    }
}
