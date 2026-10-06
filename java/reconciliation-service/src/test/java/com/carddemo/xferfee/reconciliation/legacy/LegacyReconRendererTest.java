package com.carddemo.xferfee.reconciliation.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.reconciliation.FeeLine;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyReconRendererTest {

    private final LegacyReconRenderer renderer = new LegacyReconRenderer();

    private static FeeLine fee(String id, String book, String amount, String fee) {
        return new FeeLine(id, "2024-06-20", book, new BigDecimal(amount), new BigDecimal(fee));
    }

    private static List<String> lines(LegacyReport report) {
        return List.of(new String(report.lineSequentialBytes(), StandardCharsets.US_ASCII).split("\n"));
    }

    @Test
    void emptyInputIsHeaderOnlyWithRc4() {
        LegacyReport report = renderer.render(List.of());
        assertThat(report.returnCode()).isEqualTo(4);
        assertThat(lines(report)).containsExactly(LegacyReconRenderer.HEADER_1, LegacyReconRenderer.HEADER_2);
        assertThat(report.sysout()).containsExactly("CBXFR03C: NO FEE RECORDS");
    }

    @Test
    void interleavedBooksSubtotalOnEveryContiguousRun() {
        LegacyReport report = renderer.render(List.of(
                fee("T1", "RETAIL", "100.00", "1.50"),
                fee("T2", "INSTL", "1000.00", "5.00"),
                fee("T3", "RETAIL", "200.00", "3.00")));
        assertThat(lines(report)).filteredOn(l -> l.startsWith(" BOOK ")).containsExactly(
                " BOOK RETAIL     SUBTOTAL AMOUNT       100.00  FEE         1.50",
                " BOOK INSTL      SUBTOTAL AMOUNT      1000.00  FEE         5.00",
                " BOOK RETAIL     SUBTOTAL AMOUNT       200.00  FEE         3.00");
        assertThat(lines(report)).last()
                .isEqualTo(" GRAND TOTAL COUNT         3 AMOUNT      1300.00  FEE         9.50");
        assertThat(report.sysout()).containsExactly("CBXFR03C: GRAND TOTAL FEE +00000000950");
        assertThat(report.returnCode()).isZero();
    }

    @Test
    void contiguousSameBookGetsOneSubtotal() {
        LegacyReport report = renderer.render(List.of(
                fee("T1", "INSTL", "10.00", "0.05"),
                fee("T2", "INSTL", "20.00", "0.10")));
        assertThat(lines(report)).filteredOn(l -> l.startsWith(" BOOK ")).containsExactly(
                " BOOK INSTL      SUBTOTAL AMOUNT        30.00  FEE         0.15");
    }

    @Test
    void blankBookIsFoldedIntoTheNextBookLikeCbxfr03c() {
        LegacyReport report = renderer.render(List.of(
                fee("T1", "RETAIL", "1.00", "0.01"),
                fee("T2", "", "2.00", "0.02"),
                fee("T3", "INSTL", "4.00", "0.04")));
        assertThat(lines(report)).filteredOn(l -> l.startsWith(" BOOK ")).containsExactly(
                " BOOK RETAIL     SUBTOTAL AMOUNT         1.00  FEE         0.01",
                " BOOK INSTL      SUBTOTAL AMOUNT         6.00  FEE         0.06");
    }

    @Test
    void recordsAreFixed133WithCarriageControlByte() {
        LegacyReport report = renderer.render(List.of(fee("T1", "RETAIL", "1.00", "0.01")));
        assertThat(report.records()).allSatisfy(r -> {
            assertThat(r).hasSize(LegacyReport.LRECL);
            assertThat(r.charAt(0)).isEqualTo(' ');
        });
        assertThat(report.fixedBlockBytes()).hasSize(LegacyReport.LRECL * report.records().size());
    }

    @Test
    void negativeAmountsUseTrailingMinus() {
        LegacyReport report = renderer.render(List.of(fee("T1", "RETAIL", "-5.00", "-0.06")));
        assertThat(lines(report).get(2)).isEqualTo(" T1               2024-06-20 RETAIL             5.00-         0.06-");
        assertThat(report.sysout()).containsExactly("CBXFR03C: GRAND TOTAL FEE -00000000006");
    }

    @Test
    void grandTotalWrapsAtNineIntegerDigits() {
        LegacyReport report = renderer.render(List.of(
                fee("T1", "INSTL", "999999999.99", "500.00"),
                fee("T2", "INSTL", "1.01", "0.01")));
        assertThat(lines(report)).last()
                .isEqualTo(" GRAND TOTAL COUNT         2 AMOUNT         1.00  FEE       500.01");
    }
}
