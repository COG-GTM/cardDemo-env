package com.carddemo.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A selected type-08 transfer, resolved to source/target account and book (copybook CVXFR01Y). */
public record TransferRequested(
        String tranId,
        LocalDate tranDate,
        long sourceAccountId,
        long targetAccountId,
        String bookId,
        BigDecimal amount,
        String cardNumber) {
}
