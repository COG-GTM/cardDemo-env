package com.carddemo.xferfee.cutover;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Which dates XFRDAILY is scheduled on; Control-M runs it {@code DAYS="ALL"}. */
public enum BusinessCalendar {
    DAILY,
    WEEKDAYS;

    public LocalDate previous(LocalDate date) {
        LocalDate prior = date.minusDays(1);
        if (this == WEEKDAYS) {
            while (prior.getDayOfWeek() == DayOfWeek.SATURDAY || prior.getDayOfWeek() == DayOfWeek.SUNDAY) {
                prior = prior.minusDays(1);
            }
        }
        return prior;
    }
}
