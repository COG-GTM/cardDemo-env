package com.carddemo.xferfee.reconciliation;

import com.carddemo.xferfee.contracts.TransferPosted;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** One posted fee as the report sees it (the CVXFR02Y fields CBXFR03C reads). */
public record FeeLine(String tranId, String tranDate, String bookId, BigDecimal amount, BigDecimal fee) {

    public FeeLine {
        Objects.requireNonNull(tranId, "tranId");
        Objects.requireNonNull(tranDate, "tranDate");
        Objects.requireNonNull(bookId, "bookId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(fee, "fee");
    }

    public static FeeLine of(TransferPosted posted) {
        LocalDate date = posted.tranDate();
        return new FeeLine(posted.tranId(), date == null ? "" : date.toString(), posted.bookId(),
                posted.amount(), posted.feeAmount());
    }
}
