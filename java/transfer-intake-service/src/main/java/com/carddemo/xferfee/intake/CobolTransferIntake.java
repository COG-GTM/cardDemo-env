package com.carddemo.xferfee.intake;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.RejectReason;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferIntake;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CBXFR01C (STEP010): type-08 selection (BR-01), card to source account via the cross-reference
 * (BR-02), book from the source account group (BR-03), target account from description columns
 * 14-24 (BR-04), unmatched card/account skipped with RC 4 (BR-05), business date from the origin
 * timestamp (BR-06). The 500-entry working-storage tables are kept.
 */
public final class CobolTransferIntake implements TransferIntake {

    public static final String STEP = "STEP010";
    static final int TABLE_LIMIT = 500;
    static final String TRANSFER_TYPE = "08";

    /** Outcome of one daily record: selected, rejected (with its SYSOUT line) or ignored. */
    public record Selection(Optional<TransferRequested> requested, Optional<TransferRejected> rejected,
            Optional<String> sysoutLine) {

        static Selection ignored() {
            return new Selection(Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /** Lookup tables loaded once per run, as 1000-LOAD-XREF / 1100-LOAD-ACCOUNTS do. */
    public static final class ReferenceData {
        private final List<CardXref> xrefs;
        private final List<Account> accounts;

        public ReferenceData(List<CardXref> xrefs, List<Account> accounts) {
            this.xrefs = xrefs.stream().limit(TABLE_LIMIT).toList();
            this.accounts = accounts.stream().limit(TABLE_LIMIT).toList();
        }

        Optional<CardXref> card(String cardNumber) {
            String wanted = pad(cardNumber, 16);
            return xrefs.stream().filter(x -> pad(x.cardNumber(), 16).equals(wanted)).findFirst();
        }

        Optional<Account> account(long accountId) {
            return accounts.stream().filter(a -> a.accountId() == accountId).findFirst();
        }
    }

    @Override
    public IntakeResult extract(List<DailyTransaction> dailyTransactions, List<CardXref> cardXrefs,
            List<Account> accounts) {
        ReferenceData reference = new ReferenceData(cardXrefs, accounts);
        List<TransferRequested> requested = new ArrayList<>();
        List<TransferRejected> rejected = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (DailyTransaction transaction : dailyTransactions) {
            Selection selection = select(transaction, reference);
            selection.requested().ifPresent(requested::add);
            selection.rejected().ifPresent(rejected::add);
            selection.sysoutLine().ifPresent(lines::add);
        }
        return new IntakeResult(requested, rejected,
                report(lines, dailyTransactions.size(), requested.size(), rejected.size()));
    }

    /** 2000-EXTRACT / 2100-WRITE-TRANSFER for a single record. */
    public Selection select(DailyTransaction transaction, ReferenceData reference) {
        if (!TRANSFER_TYPE.equals(transaction.typeCode())) {
            return Selection.ignored();
        }
        Optional<CardXref> xref = reference.card(transaction.cardNumber());
        if (xref.isEmpty()) {
            return new Selection(Optional.empty(),
                    Optional.of(new TransferRejected(transaction.tranId(), transaction.cardNumber(),
                            RejectReason.UNMATCHED_CARD, "card not in cross-reference")),
                    Optional.of("CBXFR01C: CARD NOT FOUND " + pad(transaction.cardNumber(), 16)));
        }
        long sourceAccountId = xref.get().accountId();
        Optional<Account> source = reference.account(sourceAccountId);
        if (source.isEmpty()) {
            return new Selection(Optional.empty(),
                    Optional.of(new TransferRejected(transaction.tranId(), transaction.cardNumber(),
                            RejectReason.UNKNOWN_SOURCE_ACCOUNT, "source account " + sourceAccountId)),
                    Optional.of("CBXFR01C: ACCOUNT NOT FOUND " + "%011d".formatted(sourceAccountId)));
        }
        TransferRequested request = new TransferRequested(
                transaction.tranId(),
                LocalDate.parse(pad(transaction.originTimestamp(), 26).substring(0, 10)),
                sourceAccountId,
                targetAccount(transaction.description()),
                source.get().groupId().stripTrailing(),
                transaction.amount(),
                transaction.cardNumber());
        return new Selection(Optional.of(request), Optional.empty(), Optional.empty());
    }

    /** End-of-step DISPLAYs and RC (0, or 4 when any card/account was unmatched). */
    public static StepReport report(List<String> rejectLines, long read, long selected, long unmatched) {
        List<String> sysout = new ArrayList<>(rejectLines);
        sysout.add("CBXFR01C: RECORDS READ " + "%09d".formatted(read));
        sysout.add("CBXFR01C: TRANSFERS SELECTED " + "%09d".formatted(selected));
        sysout.add("CBXFR01C: UNMATCHED CARDS " + "%09d".formatted(unmatched));
        return new StepReport(STEP, unmatched > 0 ? 4 : 0, sysout);
    }

    /** {@code MOVE TRAN-DESC(14:11) TO XFR-TGT-ACCT-ID} (PIC 9(11)). */
    static long targetAccount(String description) {
        String digits = pad(description, 100).substring(13, 24);
        StringBuilder numeric = new StringBuilder();
        for (char c : digits.toCharArray()) {
            numeric.append(Character.isDigit(c) ? c : '0');
        }
        return Long.parseLong(numeric.toString());
    }

    static String pad(String value, int length) {
        String text = value == null ? "" : value;
        return text.length() >= length ? text.substring(0, length) : text + " ".repeat(length - text.length());
    }
}
