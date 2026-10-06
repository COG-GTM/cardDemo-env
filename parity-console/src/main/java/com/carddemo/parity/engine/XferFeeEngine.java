package com.carddemo.parity.engine;

import com.carddemo.parity.records.Account;
import com.carddemo.parity.records.CardXref;
import com.carddemo.parity.records.Cobol;
import com.carddemo.parity.records.DailyTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Java implementation of the XFRDAILY chain rules (CBXFR01C extract + XFERFEE fee/posting).
 * Holds the account master in memory so transactions can be fed one at a time.
 */
public final class XferFeeEngine {

    public static final int EXTRACT_LENGTH = 120;
    public static final int FEE_LENGTH = 100;

    private final List<CardXref> xrefs;
    private final Map<Long, Account> accounts = new LinkedHashMap<>();
    private final FeeRuleTable rules;
    private final FeeRounding rounding;

    public XferFeeEngine(List<CardXref> xrefs, List<Account> accounts, FeeRuleTable rules, FeeRounding rounding) {
        this.xrefs = List.copyOf(xrefs);
        for (Account account : accounts) {
            this.accounts.putIfAbsent(account.id(), account.copy());
        }
        this.rules = rules;
        this.rounding = rounding;
    }

    public List<Account> accounts() {
        return List.copyOf(accounts.values());
    }

    public Optional<Account> account(long id) {
        return Optional.ofNullable(accounts.get(id));
    }

    public TransferOutcome process(DailyTransaction tran) {
        if (!"08".equals(tran.typeCode())) {
            return skipped(tran, "not a transfer (type " + tran.typeCode() + ")", List.of());
        }
        Optional<CardXref> xref = xrefs.stream()
                .filter(x -> x.cardNumber().equals(tran.cardNumber()))
                .findFirst();
        if (xref.isEmpty()) {
            return skipped(tran, "card not found", List.of("CBXFR01C: CARD NOT FOUND " + tran.cardNumber()));
        }
        long srcId = xref.get().accountId();
        Account src = accounts.get(srcId);
        if (src == null) {
            return skipped(tran, "account not found",
                    List.of("CBXFR01C: ACCOUNT NOT FOUND " + String.format("%011d", srcId)));
        }
        String book = src.book();
        String tranDate = tran.origTimestamp().substring(0, 10);
        byte[] extract = extractRecord(tran, tranDate, srcId, book);
        long tgtId = Cobol.unsigned(extract, 37, 11);
        BigDecimal amount = tran.amount();

        FeeRule rule = rules.lookup(book, LocalDate.parse(tranDate))
                .orElseThrow(() -> new IllegalStateException("XFERFEE: NO FEE RULE FOR BOOK " + book));
        BigDecimal fee = new BigDecimal("0.00");
        String capApplied = "N";
        if (amount.signum() != 0) {
            fee = amount.multiply(rule.pct()).setScale(2, rounding.mode());
            if (fee.compareTo(rule.cap()) > 0) {
                fee = rule.cap().setScale(2);
                capApplied = "Y";
            }
        }
        Account tgt = accounts.get(tgtId);
        if (tgt == null) {
            throw new IllegalStateException("XFERFEE: ACCOUNT NOT FOUND " + srcId + " / " + tgtId);
        }
        src.debit(amount, fee);
        tgt.credit(amount);

        byte[] feeRecord = feeRecord(extract, rule, fee, capApplied);
        LedgerRow ledger = new LedgerRow(tran.tranId(), tranDate, srcId, tgtId, book,
                amount.setScale(2), fee, capApplied);
        return new TransferOutcome(tran.tranId(), tran.typeCode(), true, null, book, amount,
                rule.pct(), rule.cap(), rule.effective().toString(), fee, capApplied, srcId, tgtId,
                src.balance(), tgt.balance(), ledger, extract, feeRecord, List.of());
    }

    private TransferOutcome skipped(DailyTransaction tran, String reason, List<String> messages) {
        return new TransferOutcome(tran.tranId(), tran.typeCode(), false, reason, null, tran.amount(),
                null, null, null, null, null, null, null, null, null, null, null, null, messages);
    }

    private static byte[] extractRecord(DailyTransaction tran, String tranDate, long srcId, String book) {
        byte[] out = new byte[EXTRACT_LENGTH];
        Cobol.putText(out, 0, 16, tran.tranId());
        Cobol.putText(out, 16, 10, tranDate);
        Cobol.putUnsigned(out, 26, 11, srcId);
        Cobol.copy(tran.targetAccountField(), 0, out, 37, 11);
        Cobol.putText(out, 48, 10, book);
        Cobol.copy(tran.amountField(), 0, out, 58, 11);
        Cobol.putText(out, 69, 16, tran.cardNumber());
        return out;
    }

    private static byte[] feeRecord(byte[] extract, FeeRule rule, BigDecimal fee, String capApplied) {
        byte[] out = new byte[FEE_LENGTH];
        Cobol.copy(extract, 0, out, 0, 58);
        Cobol.putPacked(out, 58, 6, 2, Cobol.zoned(extract, 58, 11, 2));
        Cobol.putPacked(out, 64, 4, 6, rule.pct());
        Cobol.putPacked(out, 68, 6, 2, fee);
        Cobol.putText(out, 74, 1, capApplied);
        Cobol.putText(out, 75, 10, rule.effective().toString());
        return out;
    }

    public static List<byte[]> split(byte[] data, int length) {
        List<byte[]> records = new ArrayList<>();
        for (int offset = 0; offset + length <= data.length; offset += length) {
            records.add(Arrays.copyOfRange(data, offset, offset + length));
        }
        return records;
    }
}
