package com.carddemo.xferfee.cutover;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runbook gate G1: the latest {@code requiredDays} scheduled business days up to {@code asOf}
 * all have a clean (PASS) shadow run with no gaps, and that window spans a fee-rate change.
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

    /**
     * @param asOf the latest business date whose shadow run must already exist (normally the
     *             last completed scheduled day before the go/no-go call). Reports after it are
     *             ignored; a missing report for it, or for any scheduled day in the window, breaks
     *             the streak, so stale history cannot keep the gate green.
     */
    public Result evaluate(List<ShadowDay> history, LocalDate asOf) {
        List<String> reasons = new ArrayList<>();
        if (history.isEmpty()) {
            reasons.add("no shadow-run reports found");
            return new Result(false, requiredDays, 0, null, null, false, Optional.empty(), reasons);
        }
        List<ShadowDay> sorted = history.stream().sorted((a, b) -> a.date().compareTo(b.date())).toList();
        LocalDate anchor = calendar.onOrBefore(asOf);
        int streak = 0;
        LocalDate expected = anchor;
        List<ShadowDay> window = new ArrayList<>();
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
            if (window.size() < requiredDays) {
                window.add(day);
            }
            expected = calendar.previous(day.date());
        }
        if (streak == 0 && breakReason == null) {
            breakReason = "missing shadow run for " + expected;
        }
        LocalDate windowStart = window.isEmpty() ? null : window.get(window.size() - 1).date();
        LocalDate windowEnd = window.isEmpty() ? null : anchor;
        boolean rateChange = !window.isEmpty() && window.stream()
                .flatMap(d -> d.rateChangeDates().stream())
                .anyMatch(d -> !d.isBefore(windowStart) && !d.isAfter(windowEnd));
        if (streak < requiredDays) {
            reasons.add("clean streak " + streak + " < required " + requiredDays
                    + (breakReason == null ? "" : " (" + breakReason + ")"));
        }
        if (requireRateChange && !rateChange) {
            reasons.add("latest " + requiredDays + "-day window does not span a CTL_XFER_PARM rate change");
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
