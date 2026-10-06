package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.AccountPosting;
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

/**
 * Target (per-transfer) posting from the COG-1249 recommendations: every transfer is its own unit
 * of work (D1); a transfer the legacy step would abend on goes to the DLQ and the run continues
 * (D2); an identical redelivery of an already-ledgered TRAN_ID is a no-op, a different payload
 * under the same id is rejected (D3). Pricing and balance arithmetic are delegated unchanged.
 */
public final class PerTransferPosting implements AccountPosting {

    /** What happened to one transfer. */
    public sealed interface Outcome {
        record Posted(TransferPosted posted, List<Account> masterAfter, List<LedgerEntry> ledgerAfter)
                implements Outcome {
        }

        record Replayed(String tranId) implements Outcome {
        }

        record Rejected(TransferRejected rejected) implements Outcome {
        }
    }

    private final AccountPosting legacy;

    public PerTransferPosting(AccountPosting legacy) {
        this.legacy = legacy;
    }

    public Outcome postOne(TransferRequested transfer, List<Account> master, List<LedgerEntry> ledger) {
        PostingResult result = legacy.post(List.of(transfer), master, ledger);
        if (result.report().returnCode() <= 4) {
            return new Outcome.Posted(result.posted().get(0), result.accountMasterAfter(), result.ledgerAfter());
        }
        TransferRejected rejected = result.rejected().get(0);
        if (rejected.reason() == RejectReason.DUPLICATE_TRAN_ID && identicalInLedger(transfer, ledger)) {
            return new Outcome.Replayed(transfer.tranId());
        }
        return new Outcome.Rejected(rejected);
    }

    @Override
    public PostingResult post(List<TransferRequested> transfers, List<Account> accountMaster,
            List<LedgerEntry> ledgerBefore) {
        List<Account> master = accountMaster;
        List<LedgerEntry> ledger = ledgerBefore;
        List<TransferPosted> posted = new ArrayList<>();
        List<TransferRejected> rejected = new ArrayList<>();
        for (TransferRequested transfer : transfers) {
            Outcome outcome = postOne(transfer, master, ledger);
            if (outcome instanceof Outcome.Posted p) {
                posted.add(p.posted());
                master = p.masterAfter();
                ledger = p.ledgerAfter();
            } else if (outcome instanceof Outcome.Rejected r) {
                rejected.add(r.rejected());
            }
        }
        return new PostingResult(posted, master, ledger, rejected, summary(posted, rejected));
    }

    /** Legacy totals plus one line per dead-lettered transfer; RC 4 when anything was rejected. */
    public static StepReport summary(List<TransferPosted> posted, List<TransferRejected> rejected) {
        BigDecimal total = posted.stream().map(TransferPosted::feeAmount).reduce(BigDecimal.ZERO.setScale(2),
                BigDecimal::add);
        List<String> sysout = new ArrayList<>();
        for (TransferRejected r : rejected) {
            sysout.add("XFERFEE: DLQ " + CobolAccountPosting.pad(r.tranId(), 16) + " " + r.reason());
        }
        sysout.add("XFERFEE: TRANSFERS POSTED " + "%09d".formatted(posted.size()));
        sysout.add("XFERFEE: TOTAL FEES " + CobolAccountPosting.signedDisplay(total, 9, 2));
        if (!rejected.isEmpty()) {
            sysout.add("XFERFEE: TRANSFERS REJECTED " + "%09d".formatted(rejected.size()));
        }
        return new StepReport(CobolAccountPosting.STEP, rejected.isEmpty() ? 0 : 4, sysout);
    }

    private static boolean identicalInLedger(TransferRequested transfer, List<LedgerEntry> ledger) {
        Optional<LedgerEntry> existing = ledger.stream()
                .filter(entry -> entry.tranId().stripTrailing().equals(transfer.tranId().stripTrailing()))
                .findFirst();
        return existing.filter(entry -> entry.tranDate().equals(transfer.tranDate())
                && entry.sourceAccountId() == transfer.sourceAccountId()
                && entry.targetAccountId() == transfer.targetAccountId()
                && entry.bookId().stripTrailing().equals(transfer.bookId().stripTrailing())
                && entry.amount().compareTo(transfer.amount()) == 0).isPresent();
    }
}
