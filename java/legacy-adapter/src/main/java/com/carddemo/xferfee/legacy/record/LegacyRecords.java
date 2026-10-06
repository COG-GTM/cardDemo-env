package com.carddemo.xferfee.legacy.record;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRequested;
import com.carddemo.xferfee.legacy.codec.DecodedRecord;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mapping between the frozen contract records and copybook field maps. PIC X values lose their
 * trailing spaces on the way in and are re-padded by the codec on the way out.
 */
public final class LegacyRecords {

    private LegacyRecords() {
    }

    public static DailyTransaction dailyTransaction(DecodedRecord r) {
        return new DailyTransaction(
                r.string("DALYTRAN-ID"),
                r.string("DALYTRAN-TYPE-CD"),
                r.intValue("DALYTRAN-CAT-CD"),
                r.string("DALYTRAN-SOURCE"),
                r.string("DALYTRAN-DESC"),
                r.decimal("DALYTRAN-AMT"),
                r.longValue("DALYTRAN-MERCHANT-ID"),
                r.string("DALYTRAN-MERCHANT-NAME"),
                r.string("DALYTRAN-MERCHANT-CITY"),
                r.string("DALYTRAN-MERCHANT-ZIP"),
                r.string("DALYTRAN-CARD-NUM"),
                r.string("DALYTRAN-ORIG-TS"),
                r.string("DALYTRAN-PROC-TS"));
    }

    public static Map<String, Object> dailyTransactionFields(DailyTransaction t) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("DALYTRAN-ID", t.tranId());
        fields.put("DALYTRAN-TYPE-CD", t.typeCode());
        fields.put("DALYTRAN-CAT-CD", t.categoryCode());
        fields.put("DALYTRAN-SOURCE", t.source());
        fields.put("DALYTRAN-DESC", t.description());
        fields.put("DALYTRAN-AMT", t.amount());
        fields.put("DALYTRAN-MERCHANT-ID", t.merchantId());
        fields.put("DALYTRAN-MERCHANT-NAME", t.merchantName());
        fields.put("DALYTRAN-MERCHANT-CITY", t.merchantCity());
        fields.put("DALYTRAN-MERCHANT-ZIP", t.merchantZip());
        fields.put("DALYTRAN-CARD-NUM", t.cardNumber());
        fields.put("DALYTRAN-ORIG-TS", t.originTimestamp());
        fields.put("DALYTRAN-PROC-TS", t.processTimestamp());
        return fields;
    }

    public static CardXref cardXref(DecodedRecord r) {
        return new CardXref(r.string("XREF-CARD-NUM"), r.longValue("XREF-CUST-ID"), r.longValue("XREF-ACCT-ID"));
    }

    public static Map<String, Object> cardXrefFields(CardXref x) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("XREF-CARD-NUM", x.cardNumber());
        fields.put("XREF-CUST-ID", x.customerId());
        fields.put("XREF-ACCT-ID", x.accountId());
        return fields;
    }

    public static Account account(DecodedRecord r) {
        return new Account(
                r.longValue("ACCT-ID"),
                r.string("ACCT-ACTIVE-STATUS"),
                r.decimal("ACCT-CURR-BAL"),
                r.decimal("ACCT-CREDIT-LIMIT"),
                r.decimal("ACCT-CASH-CREDIT-LIMIT"),
                r.string("ACCT-OPEN-DATE"),
                r.string("ACCT-EXPIRAION-DATE"),
                r.string("ACCT-REISSUE-DATE"),
                r.decimal("ACCT-CURR-CYC-CREDIT"),
                r.decimal("ACCT-CURR-CYC-DEBIT"),
                r.string("ACCT-ADDR-ZIP"),
                r.string("ACCT-GROUP-ID"));
    }

    public static Map<String, Object> accountFields(Account a) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ACCT-ID", a.accountId());
        fields.put("ACCT-ACTIVE-STATUS", a.activeStatus());
        fields.put("ACCT-CURR-BAL", a.currentBalance());
        fields.put("ACCT-CREDIT-LIMIT", a.creditLimit());
        fields.put("ACCT-CASH-CREDIT-LIMIT", a.cashCreditLimit());
        fields.put("ACCT-OPEN-DATE", a.openDate());
        fields.put("ACCT-EXPIRAION-DATE", a.expirationDate());
        fields.put("ACCT-REISSUE-DATE", a.reissueDate());
        fields.put("ACCT-CURR-CYC-CREDIT", a.currentCycleCredit());
        fields.put("ACCT-CURR-CYC-DEBIT", a.currentCycleDebit());
        fields.put("ACCT-ADDR-ZIP", a.addressZip());
        fields.put("ACCT-GROUP-ID", a.groupId());
        return fields;
    }

    public static TransferRequested transferRequested(DecodedRecord r) {
        return new TransferRequested(
                r.string("XFR-TRAN-ID"),
                LocalDate.parse(r.string("XFR-TRAN-DT")),
                r.longValue("XFR-SRC-ACCT-ID"),
                r.longValue("XFR-TGT-ACCT-ID"),
                r.string("XFR-BOOK-ID"),
                r.decimal("XFR-TRAN-AMT"),
                r.string("XFR-CARD-NUM"));
    }

    public static Map<String, Object> transferExtractFields(TransferRequested t) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("XFR-TRAN-ID", t.tranId());
        fields.put("XFR-TRAN-DT", t.tranDate().toString());
        fields.put("XFR-SRC-ACCT-ID", t.sourceAccountId());
        fields.put("XFR-TGT-ACCT-ID", t.targetAccountId());
        fields.put("XFR-BOOK-ID", t.bookId());
        fields.put("XFR-TRAN-AMT", t.amount());
        fields.put("XFR-CARD-NUM", t.cardNumber());
        return fields;
    }

    public static TransferPosted transferPosted(DecodedRecord r) {
        return new TransferPosted(
                r.string("XFE-TRAN-ID"),
                LocalDate.parse(r.string("XFE-TRAN-DT")),
                r.longValue("XFE-SRC-ACCT-ID"),
                r.longValue("XFE-TGT-ACCT-ID"),
                r.string("XFE-BOOK-ID"),
                r.decimal("XFE-TRAN-AMT"),
                r.decimal("XFE-FEE-PCT"),
                r.decimal("XFE-FEE-AMT"),
                "Y".equals(r.string("XFE-CAP-APPLIED")),
                LocalDate.parse(r.string("XFE-RULE-EFF-DT")));
    }

    public static Map<String, Object> transferFeeFields(TransferPosted t) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("XFE-TRAN-ID", t.tranId());
        fields.put("XFE-TRAN-DT", t.tranDate().toString());
        fields.put("XFE-SRC-ACCT-ID", t.sourceAccountId());
        fields.put("XFE-TGT-ACCT-ID", t.targetAccountId());
        fields.put("XFE-BOOK-ID", t.bookId());
        fields.put("XFE-TRAN-AMT", t.amount());
        fields.put("XFE-FEE-PCT", t.feePct());
        fields.put("XFE-FEE-AMT", t.feeAmount());
        fields.put("XFE-CAP-APPLIED", t.capApplied() ? "Y" : "N");
        fields.put("XFE-RULE-EFF-DT", t.ruleEffectiveDate().toString());
        return fields;
    }
}
