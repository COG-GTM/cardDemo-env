package com.carddemo.xferfee.recon;

import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferPosted;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * CBXFR03C (STEP030): header, one line per fee in file order, a subtotal whenever the book changes
 * between consecutive records (BR-18, not a grouped sort), grand total; an empty fee file writes the
 * headers only and ends RC 4 (BR-19). Lines are 133-column FBA records written line-sequential, so
 * trailing spaces are dropped exactly as GnuCOBOL does.
 */
public final class CobolReconciliation implements Reconciliation {

    public static final String STEP = "STEP030";
    static final String HEADER_1 = " TRANSFER FEE RECONCILIATION";
    static final String HEADER_2 = " TRANSACTION       DATE       BOOK       AMOUNT          FEE";
    static final int LINE_WIDTH = 133;

    @Override
    public ReconResult reconcile(List<TransferPosted> posted) {
        List<String> lines = new ArrayList<>();
        lines.add(line(HEADER_1));
        lines.add(line(HEADER_2));
        String lastBook = blank(10);
        BigDecimal bookAmount = zero();
        BigDecimal bookFee = zero();
        BigDecimal grandAmount = zero();
        BigDecimal grandFee = zero();
        long count = 0;
        for (TransferPosted fee : posted) {
            String book = pad(fee.bookId(), 10);
            if (!lastBook.isBlank() && !lastBook.equals(book)) {
                lines.add(subtotal(lastBook, bookAmount, bookFee));
                bookAmount = zero();
                bookFee = zero();
                lastBook = book;
            }
            if (lastBook.isBlank()) {
                lastBook = book;
            }
            count++;
            BigDecimal amount = packed(fee.amount());
            BigDecimal feeAmount = packed(fee.feeAmount());
            grandAmount = grandAmount.add(amount);
            bookAmount = bookAmount.add(amount);
            grandFee = grandFee.add(feeAmount);
            bookFee = bookFee.add(feeAmount);
            lines.add(line(" " + pad(fee.tranId(), 16) + " " + pad(fee.tranDate().toString(), 10) + " "
                    + book + " " + edit(amount) + " " + edit(feeAmount)));
        }
        if (count == 0) {
            return new ReconResult(lines, new StepReport(STEP, 4, List.of("CBXFR03C: NO FEE RECORDS")));
        }
        lines.add(subtotal(lastBook, bookAmount, bookFee));
        lines.add(line(" GRAND TOTAL COUNT " + countEdit(count) + " AMOUNT " + edit(grandAmount)
                + " FEE " + edit(grandFee)));
        return new ReconResult(lines, new StepReport(STEP, 0,
                List.of("CBXFR03C: GRAND TOTAL FEE " + signedDisplay(grandFee))));
    }

    private static String subtotal(String book, BigDecimal amount, BigDecimal fee) {
        return line(" BOOK " + book + " SUBTOTAL AMOUNT " + edit(amount) + " FEE " + edit(fee));
    }

    /** PIC Z(8)9.99- : zero-suppressed, trailing minus or space; S9(09)V99 truncates high-order digits. */
    static String edit(BigDecimal value) {
        BigDecimal truncated = packed(value);
        long cents = truncated.abs().movePointRight(2).longValueExact();
        String integer = Long.toString(cents / 100);
        String text = " ".repeat(9 - integer.length()) + integer + "." + "%02d".formatted(cents % 100);
        return text + (truncated.signum() < 0 ? "-" : " ");
    }

    /** PIC Z(8)9. */
    static String countEdit(long count) {
        String digits = Long.toString(count % 1_000_000_000L);
        return " ".repeat(9 - digits.length()) + digits;
    }

    static String signedDisplay(BigDecimal value) {
        long cents = packed(value).abs().movePointRight(2).longValueExact();
        return (value.signum() < 0 ? "-" : "+") + "%011d".formatted(cents);
    }

    private static BigDecimal packed(BigDecimal value) {
        BigDecimal cents = value.setScale(2, RoundingMode.DOWN);
        return cents.remainder(new BigDecimal("1000000000"));
    }

    private static String line(String text) {
        String record = text.length() > LINE_WIDTH ? text.substring(0, LINE_WIDTH) : text;
        return record.stripTrailing();
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static String blank(int length) {
        return " ".repeat(length);
    }

    static String pad(String value, int length) {
        String text = value == null ? "" : value;
        return text.length() >= length ? text.substring(0, length) : text + " ".repeat(length - text.length());
    }
}
