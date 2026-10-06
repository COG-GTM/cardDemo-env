package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps contract records to and from copybook-keyed JSON-lines rows. Keys are the copybook field
 * names so {@code tools/parity/copybook.py} can decode/encode them generically.
 */
final class LegacyRecords {

    static final String EXTRACT_DSN = "AWS.M2.CARDDEMO.XFER.EXTRACT";
    static final String ACCTDATA_XFER_DSN = "AWS.M2.CARDDEMO.ACCTDATA.XFER";
    static final String FEES_DSN = "AWS.M2.CARDDEMO.XFER.FEES";
    static final String RECON_DSN = "AWS.M2.CARDDEMO.XFER.RECON.RPT";

    private LegacyRecords() {
    }

    static DailyTransaction dailyTransaction(Map<String, String> row) {
        return new DailyTransaction(
                row.get("TRAN-ID"),
                row.get("TRAN-TYPE-CD"),
                Integer.parseInt(row.get("TRAN-CAT-CD")),
                row.get("TRAN-SOURCE"),
                row.get("TRAN-DESC"),
                new BigDecimal(row.get("TRAN-AMT")),
                Long.parseLong(row.get("TRAN-MERCHANT-ID")),
                row.get("TRAN-MERCHANT-NAME"),
                row.get("TRAN-MERCHANT-CITY"),
                row.get("TRAN-MERCHANT-ZIP"),
                row.get("TRAN-CARD-NUM"),
                row.get("TRAN-ORIG-TS"),
                row.get("TRAN-PROC-TS"));
    }

    static CardXref cardXref(Map<String, String> row) {
        return new CardXref(
                row.get("XREF-CARD-NUM"),
                Long.parseLong(row.get("XREF-CUST-ID")),
                Long.parseLong(row.get("XREF-ACCT-ID")));
    }

    static Account account(Map<String, String> row) {
        return new Account(
                Long.parseLong(row.get("ACCT-ID")),
                row.get("ACCT-ACTIVE-STATUS"),
                new BigDecimal(row.get("ACCT-CURR-BAL")),
                new BigDecimal(row.get("ACCT-CREDIT-LIMIT")),
                new BigDecimal(row.get("ACCT-CASH-CREDIT-LIMIT")),
                row.get("ACCT-OPEN-DATE"),
                row.get("ACCT-EXPIRAION-DATE"),
                row.get("ACCT-REISSUE-DATE"),
                new BigDecimal(row.get("ACCT-CURR-CYC-CREDIT")),
                new BigDecimal(row.get("ACCT-CURR-CYC-DEBIT")),
                row.get("ACCT-ADDR-ZIP"),
                row.get("ACCT-GROUP-ID"));
    }

    static Map<String, String> accountRow(Account account) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("ACCT-ID", Long.toString(account.accountId()));
        row.put("ACCT-ACTIVE-STATUS", account.activeStatus());
        row.put("ACCT-CURR-BAL", account.currentBalance().toPlainString());
        row.put("ACCT-CREDIT-LIMIT", account.creditLimit().toPlainString());
        row.put("ACCT-CASH-CREDIT-LIMIT", account.cashCreditLimit().toPlainString());
        row.put("ACCT-OPEN-DATE", account.openDate());
        row.put("ACCT-EXPIRAION-DATE", account.expirationDate());
        row.put("ACCT-REISSUE-DATE", account.reissueDate());
        row.put("ACCT-CURR-CYC-CREDIT", account.currentCycleCredit().toPlainString());
        row.put("ACCT-CURR-CYC-DEBIT", account.currentCycleDebit().toPlainString());
        row.put("ACCT-ADDR-ZIP", account.addressZip());
        row.put("ACCT-GROUP-ID", account.groupId());
        return row;
    }

    static TransferRequested transferRequested(Map<String, String> row) {
        return new TransferRequested(
                row.get("XFR-TRAN-ID"),
                LocalDate.parse(row.get("XFR-TRAN-DT")),
                Long.parseLong(row.get("XFR-SRC-ACCT-ID")),
                Long.parseLong(row.get("XFR-TGT-ACCT-ID")),
                row.get("XFR-BOOK-ID"),
                new BigDecimal(row.get("XFR-TRAN-AMT")),
                row.get("XFR-CARD-NUM"));
    }

    static Map<String, String> extractRow(TransferRequested transfer) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("XFR-TRAN-ID", transfer.tranId());
        row.put("XFR-TRAN-DT", transfer.tranDate().toString());
        row.put("XFR-SRC-ACCT-ID", Long.toString(transfer.sourceAccountId()));
        row.put("XFR-TGT-ACCT-ID", Long.toString(transfer.targetAccountId()));
        row.put("XFR-BOOK-ID", transfer.bookId());
        row.put("XFR-TRAN-AMT", transfer.amount().toPlainString());
        row.put("XFR-CARD-NUM", transfer.cardNumber());
        return row;
    }

    static TransferPosted transferPosted(Map<String, String> row) {
        return new TransferPosted(
                row.get("XFE-TRAN-ID"),
                LocalDate.parse(row.get("XFE-TRAN-DT")),
                Long.parseLong(row.get("XFE-SRC-ACCT-ID")),
                Long.parseLong(row.get("XFE-TGT-ACCT-ID")),
                row.get("XFE-BOOK-ID"),
                new BigDecimal(row.get("XFE-TRAN-AMT")),
                new BigDecimal(row.get("XFE-FEE-PCT")),
                new BigDecimal(row.get("XFE-FEE-AMT")),
                "Y".equals(row.get("XFE-CAP-APPLIED")),
                LocalDate.parse(row.get("XFE-RULE-EFF-DT")));
    }

    static Map<String, String> feeRow(TransferPosted posted) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("XFE-TRAN-ID", posted.tranId());
        row.put("XFE-TRAN-DT", posted.tranDate().toString());
        row.put("XFE-SRC-ACCT-ID", Long.toString(posted.sourceAccountId()));
        row.put("XFE-TGT-ACCT-ID", Long.toString(posted.targetAccountId()));
        row.put("XFE-BOOK-ID", posted.bookId());
        row.put("XFE-TRAN-AMT", posted.amount().toPlainString());
        row.put("XFE-FEE-PCT", posted.feePct().toPlainString());
        row.put("XFE-FEE-AMT", posted.feeAmount().toPlainString());
        row.put("XFE-CAP-APPLIED", posted.capApplied() ? "Y" : "N");
        row.put("XFE-RULE-EFF-DT", posted.ruleEffectiveDate().toString());
        return row;
    }

    static Map<String, String> textRow(String line) {
        return Map.of("line", line);
    }
}
