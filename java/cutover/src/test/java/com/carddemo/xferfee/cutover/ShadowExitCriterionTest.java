package com.carddemo.xferfee.cutover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShadowExitCriterionTest {

    private static final LocalDate START = LocalDate.of(2024, 6, 1);
    private static final LocalDate RATE_CHANGE = LocalDate.of(2024, 6, 15);

    private static ShadowDay day(LocalDate date, ShadowStatus status) {
        return new ShadowDay(date, status, Set.of(RATE_CHANGE), Path.of(date + "/report.json"));
    }

    private static List<ShadowDay> clean(int days) {
        List<ShadowDay> history = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            history.add(day(START.plusDays(i), ShadowStatus.PASS));
        }
        return history;
    }

    @Test
    void metWhenStreakLongEnoughAndSpansRateChange() {
        ShadowExitCriterion.Result result =
                new ShadowExitCriterion(20, true, BusinessCalendar.DAILY).evaluate(clean(20));
        assertTrue(result.met());
        assertEquals(20, result.cleanStreak());
        assertEquals(START, result.windowStart());
        assertTrue(result.rateChangeCovered());
    }

    @Test
    void failedDayResetsTheStreak() {
        List<ShadowDay> history = clean(25);
        history.set(10, day(history.get(10).date(), ShadowStatus.FAIL));
        ShadowExitCriterion.Result result =
                new ShadowExitCriterion(20, false, BusinessCalendar.DAILY).evaluate(history);
        assertFalse(result.met());
        assertEquals(14, result.cleanStreak());
        assertEquals(ShadowStatus.FAIL, result.lastUnclean().orElseThrow().status());
    }

    @Test
    void errorCountsAsUnclean() {
        List<ShadowDay> history = clean(5);
        history.set(4, day(history.get(4).date(), ShadowStatus.ERROR));
        ShadowExitCriterion.Result result =
                new ShadowExitCriterion(1, false, BusinessCalendar.DAILY).evaluate(history);
        assertFalse(result.met());
        assertEquals(0, result.cleanStreak());
    }

    @Test
    void missingScheduledDayBreaksTheStreak() {
        List<ShadowDay> history = clean(25);
        history.remove(12);
        ShadowExitCriterion.Result result =
                new ShadowExitCriterion(20, false, BusinessCalendar.DAILY).evaluate(history);
        assertFalse(result.met());
        assertEquals(12, result.cleanStreak());
        assertTrue(result.reasons().get(0).contains("missing shadow run for 2024-06-13"));
    }

    @Test
    void weekdayCalendarSkipsWeekends() {
        List<ShadowDay> history = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2024, 6, 3); !d.isAfter(LocalDate.of(2024, 6, 14)); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) {
                history.add(day(d, ShadowStatus.PASS));
            }
        }
        assertEquals(10, new ShadowExitCriterion(10, false, BusinessCalendar.WEEKDAYS)
                .evaluate(history).cleanStreak());
        assertEquals(5, new ShadowExitCriterion(10, false, BusinessCalendar.DAILY)
                .evaluate(history).cleanStreak());
    }

    @Test
    void rateChangeOutsideWindowIsNotCovered() {
        List<ShadowDay> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(day(LocalDate.of(2024, 7, 1).plusDays(i), ShadowStatus.PASS));
        }
        ShadowExitCriterion.Result result =
                new ShadowExitCriterion(20, true, BusinessCalendar.DAILY).evaluate(history);
        assertFalse(result.met());
        assertFalse(result.rateChangeCovered());
        assertTrue(new ShadowExitCriterion(20, false, BusinessCalendar.DAILY).evaluate(history).met());
    }

    @Test
    void emptyHistoryIsNotMet() {
        ShadowExitCriterion.Result result =
                new ShadowExitCriterion(1, false, BusinessCalendar.DAILY).evaluate(List.of());
        assertFalse(result.met());
        assertEquals(List.of("no shadow-run reports found"), result.reasons());
    }
}
