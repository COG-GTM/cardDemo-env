package com.carddemo.xferfee.cutover;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Which dates XFRDAILY is scheduled on; Control-M runs it {@code DAYS="ALL"}. */
public enum BusinessCalendar {
    DAILY,
    WEEKDAYS;

    /** The latest scheduled day on or before {@code date}. */
    public LocalDate onOrBefore(LocalDate date) {
        LocalDate day = date;
        if (this == WEEKDAYS) {
            while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                day = day.minusDays(1);
            }
        }
        return day;
    }

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
