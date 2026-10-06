package com.carddemo.xferfee.recon;

import java.util.List;

/** Output of one STEP030 execution: report lines, SYSOUT lines and return code. */
public record LegacyReconReport(List<String> lines, List<String> sysout, int returnCode) {

    public LegacyReconReport {
        lines = List.copyOf(lines);
        sysout = List.copyOf(sysout);
    }

    /** Report text as written to {@code XFER.RECON.RPT} (newline-terminated records). */
    public String text() {
        return joined(lines);
    }

    public String sysoutText() {
        return joined(sysout);
    }

    private static String joined(List<String> values) {
        StringBuilder builder = new StringBuilder();
        values.forEach(value -> builder.append(value).append('\n'));
        return builder.toString();
    }
}
