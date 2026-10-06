package com.carddemo.xferfee.shadow;

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

/**
 * STEP010 / CBXFR01C. BR-01 type 08 only; BR-02 first cross-reference match; BR-03 first account match gives the
 * book; BR-04 target account from TRAN-DESC(14:11); BR-05 unmatched card/account is logged, skipped and gives RC 4.
 * Both lookup tables hold at most 500 rows, as in the COBOL working storage.
 */
public final class LegacyTransferIntake implements TransferIntake {

    static final int TABLE_LIMIT = 500;

    @Override
    public IntakeResult extract(List<DailyTransaction> dailyTransactions, List<CardXref> cardXrefs,
            List<Account> accounts) {
        List<CardXref> xrefTable = cardXrefs.subList(0, Math.min(TABLE_LIMIT, cardXrefs.size()));
        List<Account> acctTable = accounts.subList(0, Math.min(TABLE_LIMIT, accounts.size()));
        List<TransferRequested> requested = new ArrayList<>();
        List<TransferRejected> rejected = new ArrayList<>();
        List<String> sysout = new ArrayList<>();
        for (DailyTransaction tran : dailyTransactions) {
            if (!"08".equals(tran.typeCode())) {
                continue;
            }
            CardXref xref = xrefTable.stream().filter(x -> x.cardNumber().equals(tran.cardNumber())).findFirst()
                    .orElse(null);
            if (xref == null) {
                sysout.add("CBXFR01C: CARD NOT FOUND " + tran.cardNumber());
                rejected.add(new TransferRejected(tran.tranId(), tran.cardNumber(), RejectReason.UNMATCHED_CARD,
                        "CARD NOT FOUND"));
                continue;
            }
            Account account = acctTable.stream().filter(a -> a.accountId() == xref.accountId()).findFirst()
                    .orElse(null);
            if (account == null) {
                sysout.add(String.format("CBXFR01C: ACCOUNT NOT FOUND %011d", xref.accountId()));
                rejected.add(new TransferRejected(tran.tranId(), tran.cardNumber(),
                        RejectReason.UNKNOWN_SOURCE_ACCOUNT, "ACCOUNT NOT FOUND"));
                continue;
            }
            requested.add(new TransferRequested(tran.tranId(), LocalDate.parse(tran.originTimestamp().substring(0, 10)),
                    xref.accountId(), targetAccount(tran.description()), account.groupId().strip(), tran.amount(),
                    tran.cardNumber()));
        }
        sysout.add(String.format("CBXFR01C: RECORDS READ %09d", dailyTransactions.size() % 1_000_000_000L));
        sysout.add(String.format("CBXFR01C: TRANSFERS SELECTED %09d", requested.size()));
        sysout.add(String.format("CBXFR01C: UNMATCHED CARDS %09d", rejected.size()));
        return new IntakeResult(requested, rejected, new StepReport("STEP010", rejected.isEmpty() ? 0 : 4, sysout));
    }

    private static long targetAccount(String description) {
        String digits = description.substring(13, 24).replaceAll("[^0-9]", "");
        return digits.isEmpty() ? 0 : Long.parseLong(digits);
    }
}
