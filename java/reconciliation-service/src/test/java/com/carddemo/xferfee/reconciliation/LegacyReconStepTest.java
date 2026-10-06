package com.carddemo.xferfee.reconciliation;

import static com.carddemo.xferfee.reconciliation.ReconciliationServiceTest.posted;
import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.port.ReconciliationReport;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyReconStepTest {

    private final LegacyReconStep step = new LegacyReconStep();

    @Test
    void reportLinesAreLineSequentialAndStepIsStep030() {
        ReconciliationReport report = step.reconcile(List.of(
                posted("TRN0000000000002", "RETAIL", "100.00", "1.50"),
                posted("TRN0000000000003", "INSTL", "1000.00", "5.00"),
                posted("TRN0000000000004", "RETAIL", "50.00", "0.75")));

        assertThat(report.lines()).containsExactly(
                " TRANSFER FEE RECONCILIATION",
                " TRANSACTION       DATE       BOOK       AMOUNT          FEE",
                " TRN0000000000002 2024-06-20 RETAIL           100.00          1.50",
                " BOOK RETAIL     SUBTOTAL AMOUNT       100.00  FEE         1.50",
                " TRN0000000000003 2024-06-20 INSTL           1000.00          5.00",
                " BOOK INSTL      SUBTOTAL AMOUNT      1000.00  FEE         5.00",
                " TRN0000000000004 2024-06-20 RETAIL            50.00          0.75",
                " BOOK RETAIL     SUBTOTAL AMOUNT        50.00  FEE         0.75",
                " GRAND TOTAL COUNT         3 AMOUNT      1150.00  FEE         7.25");
        assertThat(report.report().step()).isEqualTo("STEP030");
        assertThat(report.report().returnCode()).isZero();
        assertThat(report.report().sysout()).containsExactly("CBXFR03C: GRAND TOTAL FEE +00000000725");
    }

    @Test
    void emptyInputIsHeaderOnlyWithRc4() {
        ReconciliationReport report = step.reconcile(List.of());
        assertThat(report.lines()).hasSize(2);
        assertThat(report.report().returnCode()).isEqualTo(4);
        assertThat(report.report().sysout()).containsExactly("CBXFR03C: NO FEE RECORDS");
    }

    @Test
    void bypassedWhenPostingRcAboveFour() {
        assertThat(step.run(List.of(), 4)).isPresent();
        assertThat(step.run(List.of(posted("T1", "RETAIL", "1.00", "0.01")), 8)).isEmpty();
        assertThat(step.run(List.of(), 12)).isEmpty();
    }
}
