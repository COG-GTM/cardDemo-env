package com.carddemo.xferfee.cutover;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runbook gate G1: the latest {@code requiredDays} scheduled business days all have a clean
 * (PASS) shadow run with no gaps, and the clean window spans at least one fee-rate change.
 */
public final class ShadowExitCriterion {

    private final int requiredDays;
    private final boolean requireRateChange;
    private final BusinessCalendar calendar;

    public ShadowExitCriterion(int requiredDays, boolean requireRateChange, BusinessCalendar calendar) {
        if (requiredDays < 1) {
            throw new IllegalArgumentException("requiredDays must be positive");
        }
        this.requiredDays = requiredDays;
        this.requireRateChange = requireRateChange;
        this.calendar = calendar;
    }

    public Result evaluate(List<ShadowDay> history) {
        List<String> reasons = new ArrayList<>();
        if (history.isEmpty()) {
            reasons.add("no shadow-run reports found");
            return new Result(false, requiredDays, 0, null, null, false, Optional.empty(), reasons);
        }
        List<ShadowDay> sorted = history.stream().sorted((a, b) -> a.date().compareTo(b.date())).toList();
        ShadowDay latest = sorted.get(sorted.size() - 1);
        int streak = 0;
        LocalDate start = null;
        LocalDate expected = latest.date();
        Optional<ShadowDay> breaker = Optional.empty();
        String breakReason = null;
        for (int i = sorted.size() - 1; i >= 0; i--) {
            ShadowDay day = sorted.get(i);
            if (day.date().isAfter(expected)) {
                continue;
            }
            if (!day.date().equals(expected)) {
                breakReason = "missing shadow run for " + expected;
                break;
            }
            if (!day.clean()) {
                breaker = Optional.of(day);
                breakReason = "shadow run " + day.date() + " is " + day.status();
                break;
            }
            streak++;
            start = day.date();
            expected = calendar.previous(day.date());
        }
        LocalDate windowStart = start;
        LocalDate windowEnd = streak > 0 ? latest.date() : null;
        boolean rateChange = streak > 0 && sorted.stream()
                .filter(d -> !d.date().isBefore(windowStart) && !d.date().isAfter(windowEnd))
                .flatMap(d -> d.rateChangeDates().stream())
                .anyMatch(d -> !d.isBefore(windowStart) && !d.isAfter(windowEnd));
        if (streak < requiredDays) {
            reasons.add("clean streak " + streak + " < required " + requiredDays
                    + (breakReason == null ? "" : " (" + breakReason + ")"));
        }
        if (requireRateChange && !rateChange) {
            reasons.add("clean window does not span a CTL_XFER_PARM rate change");
        }
        boolean met = streak >= requiredDays && (!requireRateChange || rateChange);
        return new Result(met, requiredDays, streak, windowStart, windowEnd, rateChange, breaker, reasons);
    }

    public record Result(
            boolean met,
            int requiredDays,
            int cleanStreak,
            LocalDate windowStart,
            LocalDate windowEnd,
            boolean rateChangeCovered,
            Optional<ShadowDay> lastUnclean,
            List<String> reasons) {

        public Result {
            reasons = List.copyOf(reasons);
        }
    }
}
