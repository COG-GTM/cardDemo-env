package com.carddemo.xferfee.events;

/** End of a run's daily file: the number of {@code DailyTransaction} records published before it. */
public record BatchClosed(long recordCount) {
}
