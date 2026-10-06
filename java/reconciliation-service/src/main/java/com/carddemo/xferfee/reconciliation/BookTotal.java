package com.carddemo.xferfee.reconciliation;

import java.math.BigDecimal;

public record BookTotal(String bookId, long count, BigDecimal amount, BigDecimal fee) {

    BookTotal plus(FeeLine line) {
        return new BookTotal(bookId, count + 1, amount.add(line.amount()), fee.add(line.fee()));
    }

    static BookTotal empty(String bookId) {
        return new BookTotal(bookId, 0, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
    }
}
