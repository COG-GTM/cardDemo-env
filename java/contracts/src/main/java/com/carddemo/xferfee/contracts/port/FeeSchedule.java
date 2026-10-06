package com.carddemo.xferfee.contracts.port;

import com.carddemo.xferfee.contracts.FeeRule;
import java.time.LocalDate;
import java.util.Optional;

/** XFERFEE rule lookup: the CTL_XFER_PARM row for a book on a transfer date. */
public interface FeeSchedule {

    Optional<FeeRule> ruleFor(String bookId, LocalDate tranDate);
}
