package com.carddemo.xferfee.parity.interim;

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

/** CBXFR01C (BR-01..BR-05). Replaced by transfer-intake-service (COG-1237). */
class InterimTransferIntake implements TransferIntake {

    @Override
    public IntakeResult extract(List<DailyTransaction> dailyTransactions, List<CardXref> cardXrefs, List<Account> accounts) {
        List<TransferRequested> requested = new ArrayList<>();
        List<TransferRejected> rejected = new ArrayList<>();
        List<String> sysout = new ArrayList<>();
        List<CardXref> xrefTable = Cobol.table(cardXrefs);
        List<Account> accountTable = Cobol.table(accounts);
        for (DailyTransaction t : dailyTransactions) {
            if (!"08".equals(t.typeCode())) {
                continue;
            }
            Optional<CardXref> xref = xrefTable.stream().filter(x -> x.cardNumber().equals(t.cardNumber())).findFirst();
            if (xref.isEmpty()) {
                sysout.add("CBXFR01C: CARD NOT FOUND " + Cobol.pic(t.cardNumber(), 16));
                rejected.add(new TransferRejected(t.tranId(), t.cardNumber(), RejectReason.UNMATCHED_CARD, "card not in CARDXREF"));
                continue;
            }
            long source = xref.get().accountId();
            Optional<Account> account = accountTable.stream().filter(a -> a.accountId() == source).findFirst();
            if (account.isEmpty()) {
                sysout.add("CBXFR01C: ACCOUNT NOT FOUND " + Cobol.unsigned(source, 11));
                rejected.add(new TransferRejected(t.tranId(), t.cardNumber(), RejectReason.UNKNOWN_SOURCE_ACCOUNT,
                        "account " + source + " not in ACCTDATA"));
                continue;
            }
            requested.add(new TransferRequested(
                    t.tranId(),
                    LocalDate.parse(Cobol.pic(t.originTimestamp(), 10)),
                    source,
                    Long.parseLong(Cobol.pic(t.description(), 100).substring(13, 24)),
                    account.get().groupId(),
                    t.amount(),
                    t.cardNumber()));
        }
        sysout.add("CBXFR01C: RECORDS READ " + Cobol.unsigned(dailyTransactions.size(), 9));
        sysout.add("CBXFR01C: TRANSFERS SELECTED " + Cobol.unsigned(requested.size(), 9));
        sysout.add("CBXFR01C: UNMATCHED CARDS " + Cobol.unsigned(rejected.size(), 9));
        return new IntakeResult(requested, rejected, new StepReport("STEP010", rejected.isEmpty() ? 0 : 4, sysout));
    }
}
