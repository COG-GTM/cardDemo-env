package com.carddemo.xferfee.recon;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.TransferPosted;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyReconStepTest {

    static final LocalDate DAY = LocalDate.of(2024, 6, 30);

    static TransferPosted posted(String id, String book, String amount, String fee) {
        return new TransferPosted(DAY, id, "2024-06-05", 1L, 2L, book,
                new BigDecimal(amount), new BigDecimal("0.012500"), new BigDecimal(fee), false, "2024-01-01");
    }

    private final LegacyReconStep step = new LegacyReconStep();

    @Test
    void subtotalsFollowContiguousFileOrder() {
        LegacyReconReport report = step.run(List.of(
                posted("TRN0000000000001", "RETAIL", "100.00", "1.25"),
                posted("TRN0000000000002", "INSTL", "1000.00", "5.00"),
                posted("TRN0000000000003", "RETAIL", "200.00", "2.50")), 0).orElseThrow();

        assertThat(report.lines()).containsExactly(
                " TRANSFER FEE RECONCILIATION",
                " TRANSACTION       DATE       BOOK       AMOUNT          FEE",
                " TRN0000000000001 2024-06-05 RETAIL           100.00          1.25",
                " BOOK RETAIL     SUBTOTAL AMOUNT       100.00  FEE         1.25",
                " TRN0000000000002 2024-06-05 INSTL           1000.00          5.00",
                " BOOK INSTL      SUBTOTAL AMOUNT      1000.00  FEE         5.00",
                " TRN0000000000003 2024-06-05 RETAIL           200.00          2.50",
                " BOOK RETAIL     SUBTOTAL AMOUNT       200.00  FEE         2.50",
                " GRAND TOTAL COUNT         3 AMOUNT      1300.00  FEE         8.75");
        assertThat(report.sysout()).containsExactly("CBXFR03C: GRAND TOTAL FEE +00000000875");
        assertThat(report.returnCode()).isZero();
        assertThat(report.lines()).allSatisfy(line ->
                assertThat(line.length()).isLessThanOrEqualTo(LegacyReconReportRenderer.RECORD_LENGTH));
    }

    @Test
    void emptyInputWritesHeadersAndRc4() {
        LegacyReconReport report = step.run(List.of(), 0).orElseThrow();

        assertThat(report.text()).isEqualTo(" TRANSFER FEE RECONCILIATION\n"
                + " TRANSACTION       DATE       BOOK       AMOUNT          FEE\n");
        assertThat(report.sysout()).containsExactly("CBXFR03C: NO FEE RECORDS");
        assertThat(report.returnCode()).isEqualTo(4);
    }

    @Test
    void bypassedOnlyWhenPostingRcAboveFour() {
        List<TransferPosted> one = List.of(posted("TRN0000000000001", "RETAIL", "1.00", "0.01"));
        assertThat(step.run(one, 4)).isPresent();
        assertThat(step.run(one, 8)).isEmpty();
        assertThat(step.run(one, 12)).isEmpty();
    }
}
