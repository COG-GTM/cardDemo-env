package com.carddemo.xferfee.parity.interim;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.RejectReason;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferIntake;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.contracts.TransferRequested;
import com.carddemo.xferfee.observability.SysoutFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CBXFR01C: BR-01..BR-05. */
public class InterimTransferIntake implements TransferIntake {

    static final String TRANSFER_TYPE = "08";

    @Override
    public IntakeResult extract(List<DailyTransaction> dailyTransactions, List<CardXref> cardXrefs,
            List<Account> accounts) {
        Map<String, Long> cardToAccount = new LinkedHashMap<>();
        for (CardXref xref : cardXrefs) {
            cardToAccount.putIfAbsent(xref.cardNumber(), xref.accountId());
        }
        Map<Long, String> accountBook = new LinkedHashMap<>();
        for (Account account : accounts) {
            accountBook.putIfAbsent(account.accountId(), account.groupId());
        }
        List<TransferRequested> requested = new ArrayList<>();
        List<TransferRejected> rejected = new ArrayList<>();
        List<String> sysout = new ArrayList<>();
        for (DailyTransaction tran : dailyTransactions) {
            if (!TRANSFER_TYPE.equals(tran.typeCode())) {
                continue;
            }
            Long source = cardToAccount.get(tran.cardNumber());
            if (source == null) {
                sysout.add("CBXFR01C: CARD NOT FOUND " + SysoutFormat.text(tran.cardNumber(), 16));
                rejected.add(new TransferRejected(tran.tranId(), tran.cardNumber(), RejectReason.UNMATCHED_CARD,
                        tran.cardNumber()));
                continue;
            }
            String book = accountBook.get(source);
            if (book == null) {
                sysout.add("CBXFR01C: ACCOUNT NOT FOUND " + SysoutFormat.accountId(source));
                rejected.add(new TransferRejected(tran.tranId(), tran.cardNumber(),
                        RejectReason.UNKNOWN_SOURCE_ACCOUNT, Long.toString(source)));
                continue;
            }
            String padded = SysoutFormat.text(tran.originTimestamp(), 10);
            requested.add(new TransferRequested(tran.tranId(), LocalDate.parse(padded.substring(0, 10)), source,
                    targetAccount(tran.description()), book.trim(), tran.amount(), tran.cardNumber()));
        }
        sysout.add("CBXFR01C: RECORDS READ " + SysoutFormat.count(dailyTransactions.size()));
        sysout.add("CBXFR01C: TRANSFERS SELECTED " + SysoutFormat.count(requested.size()));
        sysout.add("CBXFR01C: UNMATCHED CARDS " + SysoutFormat.count(rejected.size()));
        int rc = rejected.isEmpty() ? 0 : 4;
        return new IntakeResult(requested, rejected, new StepReport("STEP010", rc, sysout));
    }

    /** {@code MOVE TRAN-DESC(14:11) TO XFR-TGT-ACCT-ID}. */
    static long targetAccount(String description) {
        String digits = SysoutFormat.text(description, 24).substring(13, 24).trim();
        if (digits.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException notNumeric) {
            return 0L;
        }
    }
}
