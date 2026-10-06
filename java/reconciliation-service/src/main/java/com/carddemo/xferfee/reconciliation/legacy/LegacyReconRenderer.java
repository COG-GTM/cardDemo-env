package com.carddemo.xferfee.reconciliation.legacy;

import com.carddemo.xferfee.reconciliation.FeeLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Byte-for-byte replacement for CBXFR03C. Subtotals break on contiguous book runs in input
 * order (BR-18), so a book that appears twice gets two subtotal lines; true per-book totals are
 * served separately by the reconciliation API.
 */
public final class LegacyReconRenderer {

    static final String HEADER_1 = " TRANSFER FEE RECONCILIATION";
    static final String HEADER_2 = " TRANSACTION       DATE       BOOK       AMOUNT          FEE";
    private static final String BLANK_BOOK = CobolEdit.alnum("", 10);

    public LegacyReport render(List<FeeLine> fees) {
        List<String> records = new ArrayList<>();
        records.add(record(HEADER_1));
        records.add(record(HEADER_2));

        long count = 0;
        BigDecimal grandAmt = BigDecimal.ZERO;
        BigDecimal grandFee = BigDecimal.ZERO;
        BigDecimal bookAmt = BigDecimal.ZERO;
        BigDecimal bookFee = BigDecimal.ZERO;
        String lastBook = BLANK_BOOK;

        for (FeeLine fee : fees) {
            String book = CobolEdit.alnum(fee.bookId(), 10);
            // 1000-RECORD: a blank WS-LAST-BOOK means "no book yet", so blank-book records
            // never close a run and are folded into the next book's subtotal.
            if (!lastBook.equals(BLANK_BOOK) && !lastBook.equals(book)) {
                records.add(subtotal(lastBook, bookAmt, bookFee));
                bookAmt = BigDecimal.ZERO;
                bookFee = BigDecimal.ZERO;
                lastBook = book;
            }
            if (lastBook.equals(BLANK_BOOK)) {
                lastBook = book;
            }
            count = CobolEdit.fit9(count + 1);
            grandAmt = CobolEdit.fitS9v99(grandAmt.add(fee.amount()));
            bookAmt = CobolEdit.fitS9v99(bookAmt.add(fee.amount()));
            grandFee = CobolEdit.fitS9v99(grandFee.add(fee.fee()));
            bookFee = CobolEdit.fitS9v99(bookFee.add(fee.fee()));
            records.add(record(" " + CobolEdit.alnum(fee.tranId(), 16)
                    + " " + CobolEdit.alnum(fee.tranDate(), 10)
                    + " " + book
                    + " " + CobolEdit.editAmount(fee.amount())
                    + " " + CobolEdit.editAmount(fee.fee())));
        }

        List<String> sysout = new ArrayList<>();
        int rc;
        if (count > 0) {
            records.add(subtotal(lastBook, bookAmt, bookFee));
            records.add(record(" GRAND TOTAL COUNT " + CobolEdit.editCount(count)
                    + " AMOUNT " + CobolEdit.editAmount(grandAmt)
                    + " FEE " + CobolEdit.editAmount(grandFee)));
            sysout.add("CBXFR03C: GRAND TOTAL FEE " + CobolEdit.displaySigned(grandFee));
            rc = 0;
        } else {
            // BR-19: header-only report, RC 4.
            sysout.add("CBXFR03C: NO FEE RECORDS");
            rc = 4;
        }
        return new LegacyReport(records, sysout, rc);
    }

    private static String subtotal(String book, BigDecimal amount, BigDecimal fee) {
        return record(" BOOK " + book + " SUBTOTAL AMOUNT " + CobolEdit.editAmount(amount)
                + " FEE " + CobolEdit.editAmount(fee));
    }

    /** STRING ... INTO WS-LINE PIC X(133) after MOVE SPACES. */
    private static String record(String text) {
        return CobolEdit.alnum(text, LegacyReport.LRECL);
    }
}
