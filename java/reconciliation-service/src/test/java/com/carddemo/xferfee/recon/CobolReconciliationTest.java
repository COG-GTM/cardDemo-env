package com.carddemo.xferfee.recon;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.TransferPosted;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CobolReconciliationTest {

    private final CobolReconciliation recon = new CobolReconciliation();

    private static TransferPosted fee(String id, String book, String amount, String fee) {
        return new TransferPosted(id, LocalDate.parse("2024-06-20"), 1, 2, book, new BigDecimal(amount),
                new BigDecimal("0.015000"), new BigDecimal(fee), false, LocalDate.parse("2020-01-01"));
    }

    @Test
    void subtotalsBreakOnEveryBookChangeInFileOrder() {
        Reconciliation.ReconResult result = recon.reconcile(List.of(
                fee("T1", "RETAIL", "100.00", "1.50"),
                fee("T2", "INSTL", "1000.00", "5.00"),
                fee("T3", "RETAIL", "10.00", "0.15")));
        assertThat(result.reportLines()).containsExactly(
                " TRANSFER FEE RECONCILIATION",
                " TRANSACTION       DATE       BOOK       AMOUNT          FEE",
                " T1               2024-06-20 RETAIL           100.00          1.50",
                " BOOK RETAIL     SUBTOTAL AMOUNT       100.00  FEE         1.50",
                " T2               2024-06-20 INSTL           1000.00          5.00",
                " BOOK INSTL      SUBTOTAL AMOUNT      1000.00  FEE         5.00",
                " T3               2024-06-20 RETAIL            10.00          0.15",
                " BOOK RETAIL     SUBTOTAL AMOUNT        10.00  FEE         0.15",
                " GRAND TOTAL COUNT         3 AMOUNT      1110.00  FEE         6.65");
        assertThat(result.report().sysout()).containsExactly("CBXFR03C: GRAND TOTAL FEE +00000000665");
        assertThat(result.report().returnCode()).isZero();
    }

    @Test
    void emptyFeeFileWritesHeadersOnlyAndRc4() {
        Reconciliation.ReconResult result = recon.reconcile(List.of());
        assertThat(result.reportLines()).hasSize(2);
        assertThat(result.report().returnCode()).isEqualTo(4);
        assertThat(result.report().sysout()).containsExactly("CBXFR03C: NO FEE RECORDS");
    }

    @Test
    void negativeAmountsUseTrailingMinus() {
        assertThat(CobolReconciliation.edit(new BigDecimal("-1.50"))).isEqualTo("        1.50-");
    }
}
