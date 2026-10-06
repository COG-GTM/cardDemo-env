package com.carddemo.xferfee.contracts;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Effective-dated fee rules (CTL_XFER_PARM, BR-06). */
public interface FeeSchedule {

    /** Replaces the rule set, e.g. from a fixture's {@code db2_before/CTL_XFER_PARM.csv}. */
    void seed(List<FeeRule> rules);

    /** The rule for {@code bookId} whose window contains the transaction business date. */
    Optional<FeeRule> effectiveRule(String bookId, LocalDate businessDate);

    /** All rules ordered by book id then effective date (the {@code db2_after} dump order). */
    List<FeeRule> rules();
}
