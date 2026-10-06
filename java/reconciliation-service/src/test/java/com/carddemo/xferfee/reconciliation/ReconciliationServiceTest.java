package com.carddemo.xferfee.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReconciliationServiceTest {

    private static final LocalDate DAY = LocalDate.of(2024, 6, 30);
    private final ReconciliationService service = new ReconciliationService();

    static TransferPosted posted(String id, String book, String amount, String fee) {
        return new TransferPosted(id, LocalDate.of(2024, 6, 20), 1L, 2L, book, new BigDecimal(amount),
                new BigDecimal("0.015000"), new BigDecimal(fee), false, LocalDate.of(2024, 6, 15));
    }

    @Test
    void truePerBookTotalsMergeInterleavedRunsWhileLegacyReportKeepsFileOrder() {
        service.onPosted(DAY, posted("T1", "RETAIL    ", "100.00", "1.50"));
        service.onPosted(DAY, posted("T2", "INSTL     ", "1000.00", "5.00"));
        service.onPosted(DAY, posted("T3", "RETAIL    ", "200.00", "3.00"));

        DailyReconciliation day = service.close(DAY, 0);

        assertThat(day.status()).isEqualTo(DayStatus.CLOSED);
        assertThat(day.legacyRc()).isZero();
        assertThat(day.books()).extracting(BookTotal::bookId).containsExactly("INSTL", "RETAIL");
        assertThat(day.books().get(1).count()).isEqualTo(2);
        assertThat(day.books().get(1).amount()).isEqualByComparingTo("300.00");
        assertThat(day.books().get(1).fee()).isEqualByComparingTo("4.50");
        assertThat(day.grandTotal().fee()).isEqualByComparingTo("9.50");
        assertThat(service.legacyReport(DAY).orElseThrow().records())
                .filteredOn(r -> r.startsWith(" BOOK ")).hasSize(3);
    }

    @Test
    void emptyDayClosesAsNoFeesWithRc4() {
        service.onRejected(DAY, new TransferRejected("T9", "STEP010", TransferRejected.Reason.CARD_NOT_FOUND, "CBXFR01C: CARD NOT FOUND"));
        DailyReconciliation day = service.close(DAY, 0);
        assertThat(day.status()).isEqualTo(DayStatus.NO_FEES);
        assertThat(day.legacyRc()).isEqualTo(4);
        assertThat(day.rejected().count()).isEqualTo(1);
        assertThat(day.rejected().byReason()).containsEntry("CARD_NOT_FOUND", 1L);
        assertThat(service.legacyReport(DAY).orElseThrow().sysout()).containsExactly("CBXFR03C: NO FEE RECORDS");
    }

    @Test
    void postingRcAboveFourSkipsTheReport() {
        service.onPosted(DAY, posted("T1", "RETAIL", "100.00", "1.50"));
        DailyReconciliation day = service.close(DAY, 8);
        assertThat(day.status()).isEqualTo(DayStatus.SKIPPED);
        assertThat(day.legacyRc()).isNull();
        assertThat(service.legacyReport(DAY)).isEmpty();
    }

    @Test
    void postingRcFourStillRunsTheReport() {
        service.onPosted(DAY, posted("T1", "RETAIL", "100.00", "1.50"));
        assertThat(service.close(DAY, 4).status()).isEqualTo(DayStatus.CLOSED);
    }

    @Test
    void redeliveredEventIsIdempotentButConflictingDuplicateIsRejected() {
        service.onPosted(DAY, posted("T1", "RETAIL", "100.00", "1.50"));
        service.onPosted(DAY, posted("T1", "RETAIL", "100.00", "1.50"));
        assertThatThrownBy(() -> service.onPosted(DAY, posted("T1", "RETAIL", "100.00", "9.99")))
                .isInstanceOf(ReconciliationException.class);
        assertThat(service.close(DAY, 0).grandTotal().count()).isEqualTo(1);
    }

    @Test
    void closedDayIsFrozen() {
        service.close(DAY, 0);
        assertThatThrownBy(() -> service.onPosted(DAY, posted("T1", "RETAIL", "1.00", "0.01")))
                .isInstanceOf(ReconciliationException.class)
                .extracting(e -> ((ReconciliationException) e).kind())
                .isEqualTo(ReconciliationException.Kind.DAY_CLOSED);
    }

    @Test
    void duplicateWithDifferentAccountsIsAConflict() {
        service.onPosted(DAY, posted("T1", "RETAIL", "100.00", "1.50"));
        TransferPosted moved = new TransferPosted("T1", LocalDate.of(2024, 6, 20), 3L, 4L, "RETAIL",
                new BigDecimal("100.00"), new BigDecimal("0.015000"), new BigDecimal("1.50"), false,
                LocalDate.of(2024, 6, 15));
        assertThatThrownBy(() -> service.onPosted(DAY, moved))
                .extracting(e -> ((ReconciliationException) e).kind())
                .isEqualTo(ReconciliationException.Kind.CONFLICTING_DUPLICATE);
    }

    @Test
    void identicalRedeliveryAfterCloseIsAcknowledged() {
        TransferRejected reject = new TransferRejected("T9", "STEP010", TransferRejected.Reason.CARD_NOT_FOUND, "x");
        service.onPosted(DAY, posted("T1", "RETAIL", "100.00", "1.50"));
        service.onRejected(DAY, reject);
        service.close(DAY, 0);

        service.onPosted(DAY, posted("T1", "RETAIL", "100.0", "1.5"));
        service.onRejected(DAY, reject);

        assertThat(service.find(DAY).orElseThrow().grandTotal().count()).isEqualTo(1);
        assertThatThrownBy(() -> service.onPosted(DAY, posted("T1", "RETAIL", "100.00", "9.99")))
                .extracting(e -> ((ReconciliationException) e).kind())
                .isEqualTo(ReconciliationException.Kind.CONFLICTING_DUPLICATE);
    }

    @Test
    void conflictingRejectionIsNotSilentlyDropped() {
        service.onRejected(DAY, new TransferRejected("T9", "STEP010", TransferRejected.Reason.CARD_NOT_FOUND, "x"));
        assertThatThrownBy(() -> service.onRejected(DAY,
                new TransferRejected("T9", "STEP020", TransferRejected.Reason.ACCOUNT_NOT_FOUND, "y")))
                .extracting(e -> ((ReconciliationException) e).kind())
                .isEqualTo(ReconciliationException.Kind.CONFLICTING_DUPLICATE);
    }
}
