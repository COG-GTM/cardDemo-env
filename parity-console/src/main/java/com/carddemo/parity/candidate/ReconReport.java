package com.carddemo.parity.candidate;

import com.carddemo.parity.engine.TransferOutcome;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** CBXFR03C reconciliation report (LINE SEQUENTIAL, trailing spaces trimmed). */
final class ReconReport {

    private ReconReport() {
    }

    static List<String> lines(List<TransferOutcome> fees) {
        List<String> out = new ArrayList<>();
        out.add(" TRANSFER FEE RECONCILIATION");
        out.add(" TRANSACTION       DATE       BOOK       AMOUNT          FEE");
        String lastBook = null;
        BigDecimal bookAmt = BigDecimal.ZERO;
        BigDecimal bookFee = BigDecimal.ZERO;
        BigDecimal grandAmt = BigDecimal.ZERO;
        BigDecimal grandFee = BigDecimal.ZERO;
        for (TransferOutcome fee : fees) {
            if (lastBook != null && !lastBook.equals(fee.book())) {
                out.add(subtotal(lastBook, bookAmt, bookFee));
                bookAmt = BigDecimal.ZERO;
                bookFee = BigDecimal.ZERO;
            }
            lastBook = fee.book();
            bookAmt = bookAmt.add(fee.amount());
            bookFee = bookFee.add(fee.fee());
            grandAmt = grandAmt.add(fee.amount());
            grandFee = grandFee.add(fee.fee());
            out.add(rtrim(" " + fee.tranId() + " " + fee.ledger().tranDate() + " " + fee.book() + " "
                    + edited(fee.amount()) + " " + edited(fee.fee())));
        }
        if (!fees.isEmpty()) {
            out.add(subtotal(lastBook, bookAmt, bookFee));
            out.add(rtrim(" GRAND TOTAL COUNT " + String.format("%9d", fees.size()) + " AMOUNT "
                    + edited(grandAmt) + " FEE " + edited(grandFee)));
        }
        return out;
    }

    private static String subtotal(String book, BigDecimal amount, BigDecimal fee) {
        return rtrim(" BOOK " + book + " SUBTOTAL AMOUNT " + edited(amount) + " FEE " + edited(fee));
    }

    /** PIC Z(8)9.99- */
    static String edited(BigDecimal value) {
        return String.format("%12s", value.abs().setScale(2).toPlainString()) + (value.signum() < 0 ? "-" : " ");
    }

    private static String rtrim(String line) {
        return line.stripTrailing();
    }
}
