package com.carddemo.contracts;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Resolves the effective-dated fee rule for a book (implemented by fee-schedule-service).
 * Implementations throw {@link IllegalStateException} when more than one rule matches.
 */
public interface FeeSchedule {

    Optional<FeeRule> ruleFor(String bookId, LocalDate tranDate);
}
