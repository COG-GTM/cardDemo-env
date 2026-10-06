package com.carddemo.xferfee.recon;

import com.carddemo.xferfee.contracts.TransferPosted;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders {@code XFER.RECON.RPT} exactly as CBXFR03C (STEP030) does.
 *
 * <p>Subtotals break whenever the book changes between consecutive records, so a
 * book that appears in several non-contiguous runs gets several subtotal lines
 * (legacy file-order behaviour, BR-15). Each line is a 133-byte FBA record whose
 * first byte is the carriage-control character; it is written LINE SEQUENTIAL,
 * i.e. right-trimmed and newline-terminated (BR-16). An empty input yields the
 * two headers, {@code NO FEE RECORDS} and RC 4 (BR-17).
 */
public final class LegacyReconReportRenderer {

    public static final int RECORD_LENGTH = 133;
    static final String HEADER_1 = " TRANSFER FEE RECONCILIATION";
    static final String HEADER_2 = " TRANSACTION       DATE       BOOK       AMOUNT          FEE";
    private static final String BLANK_BOOK = " ".repeat(10);

    public LegacyReconReport render(List<TransferPosted> postedInFileOrder) {
        List<String> lines = new ArrayList<>();
        lines.add(record(HEADER_1));
        lines.add(record(HEADER_2));

        long count = 0;
        BigDecimal grandAmt = BigDecimal.ZERO;
        BigDecimal grandFee = BigDecimal.ZERO;
        BigDecimal bookAmt = BigDecimal.ZERO;
        BigDecimal bookFee = BigDecimal.ZERO;
        String lastBook = BLANK_BOOK;

        for (TransferPosted posted : postedInFileOrder) {
            String book = CobolEdit.text(posted.bookId(), 10);
            if (!lastBook.isBlank() && !lastBook.equals(book)) {
                lines.add(subtotal(lastBook, bookAmt, bookFee));
                bookAmt = BigDecimal.ZERO;
                bookFee = BigDecimal.ZERO;
                lastBook = book;
            }
            if (lastBook.isBlank()) {
                lastBook = book;
            }
            count++;
            BigDecimal amount = CobolEdit.fit(posted.tranAmt(), 9, 2);
            BigDecimal fee = CobolEdit.fit(posted.feeAmt(), 9, 2);
            grandAmt = CobolEdit.fit(grandAmt.add(amount), 9, 2);
            bookAmt = CobolEdit.fit(bookAmt.add(amount), 9, 2);
            grandFee = CobolEdit.fit(grandFee.add(fee), 9, 2);
            bookFee = CobolEdit.fit(bookFee.add(fee), 9, 2);
            lines.add(record(" " + CobolEdit.text(posted.tranId(), 16)
                    + " " + CobolEdit.text(posted.tranDate(), 10)
                    + " " + book
                    + " " + CobolEdit.amount(amount)
                    + " " + CobolEdit.amount(fee)));
        }

        if (count > 0) {
            lines.add(subtotal(lastBook, bookAmt, bookFee));
            lines.add(record(" GRAND TOTAL COUNT " + CobolEdit.count(count)
                    + " AMOUNT " + CobolEdit.amount(grandAmt)
                    + " FEE " + CobolEdit.amount(grandFee)));
            return new LegacyReconReport(lines,
                    List.of("CBXFR03C: GRAND TOTAL FEE " + CobolEdit.displaySigned(grandFee)), 0);
        }
        return new LegacyReconReport(lines, List.of("CBXFR03C: NO FEE RECORDS"), 4);
    }

    private static String subtotal(String book, BigDecimal amount, BigDecimal fee) {
        return record(" BOOK " + book + " SUBTOTAL AMOUNT " + CobolEdit.amount(amount)
                + " FEE " + CobolEdit.amount(fee));
    }

    private static String record(String text) {
        String fixed = CobolEdit.text(text, RECORD_LENGTH);
        int end = fixed.length();
        while (end > 0 && fixed.charAt(end - 1) == ' ') {
            end--;
        }
        return fixed.substring(0, end);
    }
}
