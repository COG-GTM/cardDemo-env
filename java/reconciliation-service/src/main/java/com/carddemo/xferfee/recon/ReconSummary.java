package com.carddemo.xferfee.recon;

import java.time.LocalDate;
import java.util.List;

/** Response of {@code GET /recon/{businessDate}}. */
public record ReconSummary(
        LocalDate businessDate,
        List<BookTotal> books,
        BookTotal grandTotal,
        long rejectedCount) {
}
