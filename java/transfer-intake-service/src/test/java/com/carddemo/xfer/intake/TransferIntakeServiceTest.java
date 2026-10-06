package com.carddemo.xfer.intake;

import com.carddemo.xfer.contracts.TransferRejected;
import com.carddemo.xfer.contracts.TransferRequested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransferIntakeServiceTest {

    private final TransferIntakeService service = new TransferIntakeService();

    private static final List<CardCrossReference> XREF = List.of(
            new CardCrossReference("1000000000000001", "00000000001"),
            new CardCrossReference("1000000000000006", "00000000006"),
            new CardCrossReference("1000000000000009", "00000000099"));

    private static final List<AccountBook> ACCOUNTS = List.of(
            new AccountBook("00000000001", "RETAIL    "),
            new AccountBook("00000000006", "INSTL     "));

    private static DailyTransaction tran(String id, String type, String card, String amount) {
        return new DailyTransaction(
                id, type, String.format("%-100s", "XFER TO ACCT 00000000002"),
                new BigDecimal(amount), card, "2024-06-20 12:00:00.000000");
    }

    @Test
    void selectsOnlyType08AndCountsEveryRecord() {
        IntakeResult result = service.extract(List.of(
                tran("TRN0000000000001", "01", "1000000000000001", "42.00"),
                tran("TRN0000000000002", "08", "1000000000000001", "100.00"),
                tran("TRN0000000000003", "02", "1000000000000001", "1.00")), XREF, ACCOUNTS);

        assertThat(result.recordsRead()).isEqualTo(3);
        assertThat(result.requested()).containsExactly(new TransferRequested(
                "TRN0000000000002", "2024-06-20", "00000000001", "00000000002",
                "RETAIL", new BigDecimal("100.00"), "1000000000000001"));
        assertThat(result.rejected()).isEmpty();
        assertThat(result.returnCode()).isZero();
        assertThat(result.sysout()).containsExactly(
                "CBXFR01C: RECORDS READ 000000003",
                "CBXFR01C: TRANSFERS SELECTED 000000001",
                "CBXFR01C: UNMATCHED CARDS 000000000");
    }

    @Test
    void unmatchedCardAndAccountAreRejectedWithRc4AndProcessingContinues() {
        IntakeResult result = service.extract(List.of(
                tran("TRN0000000000001", "08", "9999999999999999", "10.00"),
                tran("TRN0000000000002", "08", "1000000000000009", "20.00"),
                tran("TRN0000000000003", "08", "1000000000000006", "30.00")), XREF, ACCOUNTS);

        assertThat(result.rejected()).containsExactly(
                new TransferRejected("TRN0000000000001", "9999999999999999", null,
                        TransferRejected.Reason.CARD_NOT_FOUND),
                new TransferRejected("TRN0000000000002", "1000000000000009", "00000000099",
                        TransferRejected.Reason.ACCOUNT_NOT_FOUND));
        assertThat(result.requested()).extracting(TransferRequested::tranId)
                .containsExactly("TRN0000000000003");
        assertThat(result.requested().get(0).bookId()).isEqualTo("INSTL");
        assertThat(result.returnCode()).isEqualTo(IntakeResult.RC_UNMATCHED);
        assertThat(result.sysout()).containsExactly(
                "CBXFR01C: CARD NOT FOUND 9999999999999999",
                "CBXFR01C: ACCOUNT NOT FOUND 00000000099",
                "CBXFR01C: RECORDS READ 000000003",
                "CBXFR01C: TRANSFERS SELECTED 000000001",
                "CBXFR01C: UNMATCHED CARDS 000000002");
    }

    @Test
    void firstCrossReferenceAndAccountMatchWin() {
        List<CardCrossReference> xref = List.of(
                new CardCrossReference("1000000000000001", "00000000001"),
                new CardCrossReference("1000000000000001", "00000000006"));
        List<AccountBook> accounts = List.of(
                new AccountBook("00000000001", "RETAIL"),
                new AccountBook("00000000001", "INSTL"));

        IntakeResult result = service.extract(
                List.of(tran("TRN0000000000001", "08", "1000000000000001", "1.00")), xref, accounts);

        assertThat(result.requested().get(0).sourceAccountId()).isEqualTo("00000000001");
        assertThat(result.requested().get(0).bookId()).isEqualTo("RETAIL");
    }

    @Test
    void preservesInputOrder() {
        IntakeResult result = service.extract(List.of(
                tran("TRN0000000000009", "08", "1000000000000006", "1.00"),
                tran("TRN0000000000001", "08", "1000000000000001", "2.00")), XREF, ACCOUNTS);

        assertThat(result.requested()).extracting(TransferRequested::tranId)
                .containsExactly("TRN0000000000009", "TRN0000000000001");
    }

    @Test
    void hasNo500RowTableLimit() {
        List<CardCrossReference> xref = new ArrayList<>();
        List<AccountBook> accounts = new ArrayList<>();
        for (int i = 1; i <= 750; i++) {
            xref.add(new CardCrossReference(String.format("1%015d", i), String.format("%011d", i)));
            accounts.add(new AccountBook(String.format("%011d", i), "RETAIL"));
        }

        IntakeResult result = service.extract(
                List.of(tran("TRN0000000000001", "08", String.format("1%015d", 700), "5.00")),
                xref, accounts);

        assertThat(result.rejected()).isEmpty();
        assertThat(result.requested().get(0).sourceAccountId()).isEqualTo("00000000700");
    }

    @Test
    void zeroAndNegativeAmountsPassThroughUnchanged() {
        IntakeResult result = service.extract(List.of(
                tran("TRN0000000000001", "08", "1000000000000001", "0.00"),
                tran("TRN0000000000002", "08", "1000000000000001", "-12.34")), XREF, ACCOUNTS);

        assertThat(result.requested()).extracting(TransferRequested::amount)
                .containsExactly(new BigDecimal("0.00"), new BigDecimal("-12.34"));
    }
}
