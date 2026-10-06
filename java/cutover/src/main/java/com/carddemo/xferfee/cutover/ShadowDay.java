package com.carddemo.xferfee.cutover;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;

/**
 * One COG-1242 shadow-run report ({@code work/shadow/<date>/report.json}).
 *
 * @param rateChangeDates EFF_DT values in that day's frozen CTL_XFER_PARM snapshot that
 *                        supersede an earlier rule for the same book
 */
public record ShadowDay(LocalDate date, ShadowStatus status, Set<LocalDate> rateChangeDates, Path report) {

    public ShadowDay {
        rateChangeDates = Set.copyOf(rateChangeDates);
    }

    public boolean clean() {
        return status == ShadowStatus.PASS;
    }
}
