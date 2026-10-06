package com.carddemo.xferfee.shadow;

import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferPosted;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * STEP030 / CBXFR03C. One detail line per fee record, BR-18 subtotals on each contiguous run of a book (a book that
 * reappears later gets a second subtotal), grand total, BR-19 header-only report and RC 4 on an empty day.
 */
public final class LegacyReconciliation implements Reconciliation {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    @Override
    public ReconResult reconcile(List<TransferPosted> posted) {
        List<String> lines = new ArrayList<>();
        List<String> sysout = new ArrayList<>();
        lines.add(" TRANSFER FEE RECONCILIATION");
        lines.add(" TRANSACTION       DATE       BOOK       AMOUNT          FEE");
        String lastBook = null;
        BigDecimal bookAmt = ZERO;
        BigDecimal bookFee = ZERO;
        BigDecimal grandAmt = ZERO;
        BigDecimal grandFee = ZERO;
        for (TransferPosted fee : posted) {
            String book = Snapshots.pad(fee.bookId());
            if (lastBook != null && !lastBook.equals(book)) {
                lines.add(subtotal(lastBook, bookAmt, bookFee));
                bookAmt = ZERO;
                bookFee = ZERO;
                lastBook = book;
            }
            if (lastBook == null && !book.isBlank()) {
                lastBook = book;
            }
            grandAmt = Zoned.truncate(grandAmt.add(fee.amount()), 9, 2);
            bookAmt = Zoned.truncate(bookAmt.add(fee.amount()), 9, 2);
            grandFee = Zoned.truncate(grandFee.add(fee.feeAmount()), 9, 2);
            bookFee = Zoned.truncate(bookFee.add(fee.feeAmount()), 9, 2);
            lines.add(" " + fee.tranId() + " " + fee.tranDate() + " " + book + " " + Zoned.editedMoney(fee.amount())
                    + " " + Zoned.editedMoney(fee.feeAmount()));
        }
        int rc;
        if (!posted.isEmpty()) {
            lines.add(subtotal(lastBook == null ? " ".repeat(10) : lastBook, bookAmt, bookFee));
            lines.add(" GRAND TOTAL COUNT " + Zoned.editedCount(posted.size()) + " AMOUNT "
                    + Zoned.editedMoney(grandAmt) + " FEE " + Zoned.editedMoney(grandFee));
            sysout.add("CBXFR03C: GRAND TOTAL FEE " + Zoned.signedDisplay(grandFee, 9, 2));
            rc = 0;
        } else {
            sysout.add("CBXFR03C: NO FEE RECORDS");
            rc = 4;
        }
        return new ReconResult(lines.stream().map(String::stripTrailing).toList(),
                new StepReport("STEP030", rc, sysout));
    }

    private static String subtotal(String book, BigDecimal amt, BigDecimal fee) {
        return " BOOK " + book + " SUBTOTAL AMOUNT " + Zoned.editedMoney(amt) + " FEE " + Zoned.editedMoney(fee);
    }
}
