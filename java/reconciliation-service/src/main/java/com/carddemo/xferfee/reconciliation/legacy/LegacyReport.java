package com.carddemo.xferfee.reconciliation.legacy;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Output of the legacy renderer: the XFER.RECON.RPT records (FBA, LRECL 133, column 1 is the
 * ASA carriage-control byte), the STEP030 SYSOUT lines and the step return code.
 */
public record LegacyReport(List<String> records, List<String> sysout, int returnCode) {

    public static final int LRECL = 133;

    public LegacyReport {
        records = List.copyOf(records);
        sysout = List.copyOf(sysout);
    }

    /** Fixed 133-byte records, as the FBA dataset holds them on z/OS. */
    public byte[] fixedBlockBytes() {
        StringBuilder out = new StringBuilder(records.size() * LRECL);
        records.forEach(out::append);
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * The XFERRPT file as GnuCOBOL writes it (ORGANIZATION LINE SEQUENTIAL): trailing spaces
     * removed, each record terminated by LF. This is the recorded parity form.
     */
    public byte[] lineSequentialBytes() {
        StringBuilder out = new StringBuilder();
        for (String record : records) {
            out.append(record.stripTrailing()).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public String sysoutText() {
        StringBuilder out = new StringBuilder();
        sysout.forEach(line -> out.append(line).append('\n'));
        return out.toString();
    }
}
