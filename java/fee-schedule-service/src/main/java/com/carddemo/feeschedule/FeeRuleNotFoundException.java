package com.carddemo.feeschedule;

import java.time.LocalDate;

public class FeeRuleNotFoundException extends RuntimeException {

    public FeeRuleNotFoundException(String bookId, LocalDate date) {
        super("No fee rule for book " + bookId + " effective on " + date);
    }
}
