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
import com.carddemo.xferfee.observability.SysoutFormat;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** XFERFEE: BR-06..BR-17. The first fatal condition abends the step RC 8 with nothing committed (BR-15). */
public class InterimAccountPosting implements AccountPosting {

    static final String ABEND = "XFERFEE: 9999-ABEND-PROGRAM";

    private final FeeSchedule schedule;
    private final FeePolicy policy;

    public InterimAccountPosting(FeeSchedule schedule, FeePolicy policy) {
        this.schedule = schedule;
        this.policy = policy;
    }

    @Override
    public PostingResult post(List<TransferRequested> transfers, List<Account> accountMaster,
            List<LedgerEntry> ledgerBefore) {
        Map<Long, Account> master = new LinkedHashMap<>();
        for (Account account : accountMaster) {
            master.putIfAbsent(account.accountId(), account);
        }
        Set<String> ledgerKeys = new HashSet<>();
        ledgerBefore.forEach(entry -> ledgerKeys.add(entry.tranId()));
        List<TransferPosted> posted = new ArrayList<>();
        List<LedgerEntry> ledger = new ArrayList<>(ledgerBefore);
        BigDecimal totalFees = BigDecimal.ZERO.setScale(2);
        for (TransferRequested transfer : transfers) {
            Optional<FeeRule> rule = schedule.effectiveRule(transfer.bookId(), transfer.tranDate());
            if (rule.isEmpty()) {
                return abend(accountMaster, ledgerBefore, transfer, RejectReason.NO_FEE_RULE, transfer.bookId(),
                        "XFERFEE: NO FEE RULE FOR BOOK " + SysoutFormat.text(transfer.bookId(), 10));
            }
            FeeResult fee = policy.apply(transfer.amount(), rule.get());
            Account source = master.get(transfer.sourceAccountId());
            Account target = master.get(transfer.targetAccountId());
            if (source == null || target == null) {
                String ids = SysoutFormat.accountId(transfer.sourceAccountId()) + " / "
                        + SysoutFormat.accountId(transfer.targetAccountId());
                return abend(accountMaster, ledgerBefore, transfer, RejectReason.UNKNOWN_ACCOUNT, ids,
                        "XFERFEE: ACCOUNT NOT FOUND " + ids);
            }
            if (!ledgerKeys.add(transfer.tranId())) {
                return abend(accountMaster, ledgerBefore, transfer, RejectReason.DUPLICATE_TRAN_ID,
                        transfer.tranId(), "XFERFEE: LEDGER INSERT FAILED");
            }
            BigDecimal amount = transfer.amount();
            BigDecimal feeAmount = fee.feeAmount();
            master.put(source.accountId(), withMovement(master.get(source.accountId()),
                    amount.add(feeAmount).negate(), BigDecimal.ZERO, amount.add(feeAmount)));
            master.put(target.accountId(), withMovement(master.get(target.accountId()), amount, amount,
                    BigDecimal.ZERO));
            posted.add(new TransferPosted(transfer.tranId(), transfer.tranDate(), transfer.sourceAccountId(),
                    transfer.targetAccountId(), transfer.bookId(), amount, rule.get().feePct(), feeAmount,
                    fee.capApplied(), rule.get().effectiveDate()));
            ledger.add(new LedgerEntry(transfer.tranId(), transfer.tranDate(), transfer.sourceAccountId(),
                    transfer.targetAccountId(), transfer.bookId(), amount, feeAmount, fee.capApplied()));
            totalFees = totalFees.add(feeAmount);
        }
        List<String> sysout = List.of(
                "XFERFEE: TRANSFERS POSTED " + SysoutFormat.count(posted.size()),
                "XFERFEE: TOTAL FEES " + SysoutFormat.signedMoney(totalFees));
        return new PostingResult(posted, List.copyOf(master.values()), ledger, List.of(),
                new StepReport("STEP020", 0, sysout));
    }

    private static PostingResult abend(List<Account> accountMaster, List<LedgerEntry> ledgerBefore,
            TransferRequested transfer, RejectReason reason, String detail, String message) {
        TransferRejected rejected = new TransferRejected(transfer.tranId(), transfer.cardNumber(), reason, detail);
        return new PostingResult(List.of(), accountMaster, ledgerBefore, List.of(rejected),
                new StepReport("STEP020", 8, List.of(message, ABEND)));
    }

    /** BR-11: balance += {@code balance}; cycle credit/debit += the given amounts. */
    private static Account withMovement(Account account, BigDecimal balance, BigDecimal credit, BigDecimal debit) {
        return new Account(account.accountId(), account.activeStatus(), account.currentBalance().add(balance),
                account.creditLimit(), account.cashCreditLimit(), account.openDate(), account.expirationDate(),
                account.reissueDate(), account.currentCycleCredit().add(credit),
                account.currentCycleDebit().add(debit), account.addressZip(), account.groupId());
    }
}
