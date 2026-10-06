package com.carddemo.xfer.intake;

import com.carddemo.xfer.contracts.TransferRejected;
import com.carddemo.xfer.contracts.TransferRequested;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of one intake run (STEP010).
 *
 * @param recordsRead every daily transaction read, regardless of type
 * @param requested   resolved transfers, in input order
 * @param rejected    unresolved transfers, in input order
 */
public record IntakeResult(
        long recordsRead,
        List<TransferRequested> requested,
        List<TransferRejected> rejected) {

    public static final int RC_OK = 0;
    public static final int RC_UNMATCHED = 4;

    public IntakeResult {
        requested = List.copyOf(requested);
        rejected = List.copyOf(rejected);
    }

    /** Run-level result: RC 4 when any transfer was rejected. Processing continues either way. */
    public int returnCode() {
        return rejected.isEmpty() ? RC_OK : RC_UNMATCHED;
    }

    /** STEP010 SYSOUT, line for line as CBXFR01C DISPLAYs it. */
    public List<String> sysout() {
        List<String> lines = new ArrayList<>();
        for (TransferRejected rejection : rejected) {
            lines.add(switch (rejection.reason()) {
                case CARD_NOT_FOUND -> "CBXFR01C: CARD NOT FOUND " + pad(rejection.cardNumber(), 16);
                case ACCOUNT_NOT_FOUND -> "CBXFR01C: ACCOUNT NOT FOUND " + rejection.sourceAccountId();
            });
        }
        lines.add("CBXFR01C: RECORDS READ " + counter(recordsRead));
        lines.add("CBXFR01C: TRANSFERS SELECTED " + counter(requested.size()));
        lines.add("CBXFR01C: UNMATCHED CARDS " + counter(rejected.size()));
        return lines;
    }

    private static String counter(long value) {
        return String.format("%09d", value);
    }

    private static String pad(String value, int width) {
        return String.format("%-" + width + "s", value);
    }
}
