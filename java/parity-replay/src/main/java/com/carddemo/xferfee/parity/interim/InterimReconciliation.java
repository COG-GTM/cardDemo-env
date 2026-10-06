package com.carddemo.xferfee.parity.interim;

import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferPosted;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** CBXFR03C (BR-18, BR-19). Replaced by reconciliation-service (COG-1239). */
class InterimReconciliation implements Reconciliation {

    @Override
    public ReconResult reconcile(List<TransferPosted> posted) {
        List<String> lines = new ArrayList<>();
        lines.add(" TRANSFER FEE RECONCILIATION");
        lines.add(" TRANSACTION       DATE       BOOK       AMOUNT          FEE");
        String lastBook = null;
        BigDecimal bookAmt = BigDecimal.ZERO;
        BigDecimal bookFee = BigDecimal.ZERO;
        BigDecimal grandAmt = BigDecimal.ZERO;
        BigDecimal grandFee = BigDecimal.ZERO;
        for (TransferPosted p : posted) {
            String book = Cobol.pic(p.bookId(), 10);
            if (lastBook != null && !lastBook.isBlank() && !lastBook.equals(book)) {
                lines.add(subtotal(lastBook, bookAmt, bookFee));
                bookAmt = BigDecimal.ZERO;
                bookFee = BigDecimal.ZERO;
                lastBook = book;
            }
            if (lastBook == null || lastBook.isBlank()) {
                lastBook = book;
            }
            bookAmt = bookAmt.add(p.amount());
            bookFee = bookFee.add(p.feeAmount());
            grandAmt = grandAmt.add(p.amount());
            grandFee = grandFee.add(p.feeAmount());
            lines.add(" " + Cobol.pic(p.tranId(), 16) + " " + p.tranDate() + " " + book + " "
                    + Cobol.amountEdit(p.amount()) + " " + Cobol.amountEdit(p.feeAmount()));
        }
        List<String> sysout = new ArrayList<>();
        if (posted.isEmpty()) {
            sysout.add("CBXFR03C: NO FEE RECORDS");
            return new ReconResult(trim(lines), new StepReport("STEP030", 4, sysout));
        }
        lines.add(subtotal(lastBook, bookAmt, bookFee));
        lines.add(" GRAND TOTAL COUNT " + Cobol.countEdit(posted.size()) + " AMOUNT " + Cobol.amountEdit(grandAmt)
                + " FEE " + Cobol.amountEdit(grandFee));
        sysout.add("CBXFR03C: GRAND TOTAL FEE " + Cobol.signed(grandFee));
        return new ReconResult(trim(lines), new StepReport("STEP030", 0, sysout));
    }

    private static String subtotal(String book, BigDecimal amount, BigDecimal fee) {
        return " BOOK " + book + " SUBTOTAL AMOUNT " + Cobol.amountEdit(amount) + " FEE " + Cobol.amountEdit(fee);
    }

    /** LINE SEQUENTIAL output drops trailing spaces. */
    private static List<String> trim(List<String> lines) {
        return lines.stream().map(l -> l.replaceFirst(" +$", "")).toList();
    }
}
