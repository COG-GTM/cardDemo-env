package com.carddemo.xferfee.shadow;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
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
import java.util.function.Function;
import java.util.Set;

/**
 * STEP020 / XFERFEE. BR-06/07 rule lookup (missing rule or SQL error: abend RC 8), BR-08..10 fee, BR-11 posting with
 * no active/funds/limit checks (BR-13) and last-match account lookup, BR-12 unknown account abend, BR-14 duplicate
 * TRAN_ID abend, BR-15 one commit per run, BR-16 full new master generation, BR-17 input order. Balances keep the
 * S9(10)V99 and the running fee total the S9(9)V99 high-order truncation of the COBOL receiving fields.
 * XFR-TRAN-AMT is used as GnuCOBOL reads the extract bytes ({@link LegacyAmounts}).
 */
public final class LegacyAccountPosting implements AccountPosting {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final SnapshotFeeSchedule schedule;
    private final FeePolicy feePolicy;

    /** SQLCODE ocesql reports for a PostgreSQL unique violation, as DISPLAYed by XFERFEE. */
    static final String SQLCODE_UNIQUE_VIOLATION = "-0000000403";

    /** libcob's warnings when 9999-ABEND-PROGRAM does STOP RUN with all four files still open. */
    static final List<String> IMPLICIT_CLOSE = List.of(
            "libcob: warning: implicit CLOSE of XFERFEE ('XFERFEE')",
            "libcob: warning: implicit CLOSE of ACCTOUT ('ACCTOUT')",
            "libcob: warning: implicit CLOSE of XFEREXTR ('XFEREXTR')",
            "libcob: warning: implicit CLOSE of ACCTFILE ('ACCTFILE')");

    private final Function<TransferRequested, BigDecimal> legacyAmount;

    public LegacyAccountPosting(SnapshotFeeSchedule schedule, FeePolicy feePolicy) {
        this(schedule, feePolicy, LegacyAmounts.standardOverpunch());
    }

    public LegacyAccountPosting(SnapshotFeeSchedule schedule, FeePolicy feePolicy,
            Function<TransferRequested, BigDecimal> legacyAmount) {
        this.legacyAmount = legacyAmount;
        this.schedule = schedule;
        this.feePolicy = feePolicy;
    }

    @Override
    public PostingResult post(List<TransferRequested> transfers, List<Account> accountMaster,
            List<LedgerEntry> ledgerBefore) {
        List<Account> table = new ArrayList<>(
                accountMaster.subList(0, Math.min(LegacyTransferIntake.TABLE_LIMIT, accountMaster.size())));
        List<TransferPosted> posted = new ArrayList<>();
        List<LedgerEntry> pending = new ArrayList<>();
        Set<String> ledgerIds = new HashSet<>();
        ledgerBefore.forEach(row -> ledgerIds.add(row.tranId()));
        List<String> sysout = new ArrayList<>();
        BigDecimal feeTotal = ZERO;
        for (TransferRequested xfer : transfers) {
            List<FeeRule> matches = schedule.matching(xfer.bookId(), xfer.tranDate());
            if (matches.isEmpty()) {
                sysout.add("XFERFEE: NO FEE RULE FOR BOOK " + Snapshots.pad(xfer.bookId()));
                return abend(posted, ledgerBefore, xfer, RejectReason.NO_FEE_RULE, sysout);
            }
            // ocesql's SELECT INTO does not raise -811: overlapping rows silently yield the first row in
            // table order, which is snapshot (load) order.
            FeeRule rule = matches.get(0);
            BigDecimal amount = legacyAmount.apply(xfer);
            FeeResult fee = amount.signum() != 0
                    ? feePolicy.apply(amount, rule)
                    : new FeeResult(ZERO, false);
            int src = lastIndex(table, xfer.sourceAccountId());
            int tgt = lastIndex(table, xfer.targetAccountId());
            if (src < 0 || tgt < 0) {
                sysout.add(String.format("XFERFEE: ACCOUNT NOT FOUND %011d / %011d", xfer.sourceAccountId(),
                        xfer.targetAccountId()));
                return abend(posted, ledgerBefore, xfer, RejectReason.UNKNOWN_ACCOUNT, sysout);
            }
            BigDecimal debit = amount.add(fee.feeAmount());
            table.set(src, post(table.get(src), debit.negate(), ZERO, debit));
            table.set(tgt, post(table.get(tgt), amount, amount, ZERO));
            TransferPosted row = new TransferPosted(xfer.tranId(), xfer.tranDate(), xfer.sourceAccountId(),
                    xfer.targetAccountId(), xfer.bookId(), amount, rule.feePct(), fee.feeAmount(), fee.capApplied(),
                    rule.effectiveDate());
            posted.add(row);
            if (!ledgerIds.add(xfer.tranId())) {
                sysout.add("XFERFEE: LEDGER INSERT FAILED " + SQLCODE_UNIQUE_VIOLATION);
                return abend(posted, ledgerBefore, xfer, RejectReason.DUPLICATE_TRAN_ID, sysout);
            }
            pending.add(new LedgerEntry(row.tranId(), row.tranDate(), row.sourceAccountId(), row.targetAccountId(),
                    row.bookId(), row.amount(), row.feeAmount(), row.capApplied()));
            feeTotal = Zoned.truncate(feeTotal.add(fee.feeAmount()), 9, 2);
        }
        List<LedgerEntry> ledgerAfter = new ArrayList<>(ledgerBefore);
        ledgerAfter.addAll(pending);
        sysout.add(String.format("XFERFEE: TRANSFERS POSTED %09d", posted.size() % 1_000_000_000L));
        sysout.add("XFERFEE: TOTAL FEES " + Zoned.signedDisplay(feeTotal, 9, 2));
        return new PostingResult(posted, table, ledgerAfter, List.of(), new StepReport("STEP020", 0, sysout));
    }

    /** 9999-ABEND-PROGRAM: RC 8, nothing committed, the new master generation is empty. */
    private static PostingResult abend(List<TransferPosted> posted, List<LedgerEntry> ledgerBefore,
            TransferRequested xfer, RejectReason reason, List<String> sysout) {
        String detail = sysout.get(sysout.size() - 1);
        sysout.add("XFERFEE: 9999-ABEND-PROGRAM");
        sysout.addAll(IMPLICIT_CLOSE);
        return new PostingResult(posted, List.of(), ledgerBefore,
                List.of(new TransferRejected(xfer.tranId(), xfer.cardNumber(), reason, detail)),
                new StepReport("STEP020", 8, sysout));
    }

    private static int lastIndex(List<Account> table, long accountId) {
        for (int i = table.size() - 1; i >= 0; i--) {
            if (table.get(i).accountId() == accountId) {
                return i;
            }
        }
        return -1;
    }

    private static Account post(Account a, BigDecimal balance, BigDecimal credit, BigDecimal debit) {
        return new Account(a.accountId(), a.activeStatus(), Zoned.truncate(a.currentBalance().add(balance), 10, 2),
                a.creditLimit(), a.cashCreditLimit(), a.openDate(), a.expirationDate(), a.reissueDate(),
                Zoned.truncate(a.currentCycleCredit().add(credit), 10, 2),
                Zoned.truncate(a.currentCycleDebit().add(debit), 10, 2), a.addressZip(), a.groupId());
    }
}
