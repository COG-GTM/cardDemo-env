package com.carddemo.xfer.legacy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** CBXFR03C: the legacy 133-column report, subtotalling on book changes in file order. */
public final class ReconReport {

    public record Entry(String tranId, String tranDt, String bookId, BigDecimal tranAmt,
                        BigDecimal feeAmt) {
    }

    public record Result(List<String> lines, List<String> sysout, int rc) {
    }

    private static final int LINE_LENGTH = 133;

    private ReconReport() {
    }

    public static Result render(List<Entry> entries) {
        List<String> lines = new ArrayList<>();
        lines.add(line(" TRANSFER FEE RECONCILIATION"));
        lines.add(line(" TRANSACTION       DATE       BOOK       AMOUNT          FEE"));
        BigDecimal grandAmt = BigDecimal.ZERO.setScale(2);
        BigDecimal grandFee = BigDecimal.ZERO.setScale(2);
        BigDecimal bookAmt = BigDecimal.ZERO.setScale(2);
        BigDecimal bookFee = BigDecimal.ZERO.setScale(2);
        String lastBook = blank(10);
        long count = 0;
        for (Entry e : entries) {
            String book = pad(e.bookId(), 10);
            if (!lastBook.isBlank() && !lastBook.equals(book)) {
                lines.add(subtotal(lastBook, bookAmt, bookFee));
                bookAmt = BigDecimal.ZERO.setScale(2);
                bookFee = BigDecimal.ZERO.setScale(2);
                lastBook = book;
            }
            if (lastBook.isBlank()) {
                lastBook = book;
            }
            count++;
            grandAmt = grandAmt.add(e.tranAmt());
            bookAmt = bookAmt.add(e.tranAmt());
            grandFee = grandFee.add(e.feeAmt());
            bookFee = bookFee.add(e.feeAmt());
            lines.add(line(" " + pad(e.tranId(), 16) + " " + pad(e.tranDt(), 10) + " " + book
                    + " " + Cobol.editAmount(e.tranAmt()) + " " + Cobol.editAmount(e.feeAmt())));
        }
        if (count == 0) {
            return new Result(lines, List.of("CBXFR03C: NO FEE RECORDS"), 4);
        }
        lines.add(subtotal(lastBook, bookAmt, bookFee));
        lines.add(line(" GRAND TOTAL COUNT " + Cobol.editCount(count)
                + " AMOUNT " + Cobol.editAmount(grandAmt)
                + " FEE " + Cobol.editAmount(grandFee)));
        return new Result(lines,
                List.of("CBXFR03C: GRAND TOTAL FEE " + Cobol.displaySigned(grandFee, 11, 2)), 0);
    }

    private static String subtotal(String book, BigDecimal amt, BigDecimal fee) {
        return line(" BOOK " + book + " SUBTOTAL AMOUNT " + Cobol.editAmount(amt)
                + " FEE " + Cobol.editAmount(fee));
    }

    /** LINE SEQUENTIAL writes drop trailing spaces of the 133-byte record. */
    private static String line(String value) {
        String record = value.length() > LINE_LENGTH ? value.substring(0, LINE_LENGTH) : value;
        return record.stripTrailing();
    }

    private static String pad(String value, int length) {
        String padded = String.format("%-" + length + "s", value == null ? "" : value);
        return padded.substring(0, length);
    }

    private static String blank(int length) {
        return " ".repeat(length);
    }
}
