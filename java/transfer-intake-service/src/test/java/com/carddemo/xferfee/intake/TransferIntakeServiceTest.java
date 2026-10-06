package com.carddemo.xferfee.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.RejectReason;
import com.carddemo.xferfee.contracts.TransferIntake.IntakeResult;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransferIntakeServiceTest {

    private final TransferIntakeService service = new TransferIntakeService();

    private static DailyTransaction tran(String id, String type, String card, String desc, String amount) {
        return tran(id, type, card, desc, amount, "2024-06-20 12:00:00.000000");
    }

    private static DailyTransaction tran(String id, String type, String card, String desc, String amount, String ts) {
        return new DailyTransaction(id, type, 1, "DEMO", desc, new BigDecimal(amount), 0, "", "", "", card, ts, ts);
    }

    private static Account account(long id, String group) {
        BigDecimal zero = new BigDecimal("0.00");
        return new Account(id, "Y", zero, zero, zero, "", "", "", zero, zero, "", group);
    }

    private static final List<CardXref> XREFS = List.of(
            new CardXref("1000000000000001", 1, 1),
            new CardXref("1000000000000006", 6, 6),
            new CardXref("1000000000000009", 9, 99));

    private static final List<Account> ACCOUNTS = List.of(
            account(1, "RETAIL"), account(2, "RETAIL"), account(6, "INSTL"), account(7, "INSTL"));

    @Test
    void br01SelectsOnlyType08AndCountsEveryRecordRead() {
        IntakeResult result = service.extract(List.of(
                tran("T1", "01", "1000000000000001", "POS purchase", "42.00"),
                tran("T2", "08", "1000000000000001", "XFER TO ACCT 00000000002", "100.00"),
                tran("T3", "02", "1000000000000001", "PAYMENT", "10.00"),
                tran("T4", "8", "1000000000000001", "XFER TO ACCT 00000000002", "10.00")),
                XREFS, ACCOUNTS);

        assertThat(result.requested()).extracting(TransferRequested::tranId).containsExactly("T2");
        assertThat(result.rejected()).isEmpty();
        assertThat(result.report().step()).isEqualTo("STEP010");
        assertThat(result.report().returnCode()).isZero();
        assertThat(result.report().sysout()).containsExactly(
                "CBXFR01C: RECORDS READ 000000004",
                "CBXFR01C: TRANSFERS SELECTED 000000001",
                "CBXFR01C: UNMATCHED CARDS 000000000");
    }

    @Test
    void br02To04ResolveSourceAccountBookTargetAndDate() {
        IntakeResult result = service.extract(List.of(
                tran("T1", "08", "1000000000000006", "XFER TO ACCT 00000000002", "1000.00")),
                XREFS, ACCOUNTS);

        assertThat(result.requested()).containsExactly(new TransferRequested(
                "T1", LocalDate.of(2024, 6, 20), 6, 2, "INSTL", new BigDecimal("1000.00"), "1000000000000006"));
    }

    @Test
    void br04TargetIsDescriptionPositions14Through24() {
        assertThat(TransferIntakeService.targetAccountField("XFER TO ACCT 00000000007 trailing"))
                .isEqualTo("00000000007");
        assertThat(TransferIntakeService.targetAccountField("XFER TO ACCT 123")).isEqualTo("123        ");
    }

    @Test
    void br05UnmatchedCardOrAccountIsRejectedLoggedAndProcessingContinues() {
        IntakeResult result = service.extract(List.of(
                tran("T1", "08", "4000123", "XFER TO ACCT 00000000002", "1.00"),
                tran("T2", "08", "1000000000000009", "XFER TO ACCT 00000000002", "2.00"),
                tran("T3", "08", "1000000000000001", "XFER TO ACCT 00000000002", "3.00")),
                XREFS, ACCOUNTS);

        assertThat(result.requested()).extracting(TransferRequested::tranId).containsExactly("T3");
        assertThat(result.rejected())
                .extracting(TransferRejected::tranId, TransferRejected::cardNumber, TransferRejected::reason)
                .containsExactly(
                        tuple("T1", "4000123", RejectReason.UNMATCHED_CARD),
                        tuple("T2", "1000000000000009", RejectReason.UNKNOWN_SOURCE_ACCOUNT));
        assertThat(result.report().returnCode()).isEqualTo(TransferIntakeService.RC_UNMATCHED);
        assertThat(result.report().sysout()).containsExactly(
                "CBXFR01C: CARD NOT FOUND 4000123         ",
                "CBXFR01C: ACCOUNT NOT FOUND 00000000099",
                "CBXFR01C: RECORDS READ 000000003",
                "CBXFR01C: TRANSFERS SELECTED 000000001",
                "CBXFR01C: UNMATCHED CARDS 000000002");
        assertThat(result.rejected()).extracting(TransferRejected::detail)
                .containsExactlyElementsOf(result.report().sysout().subList(0, 2));
    }

    @Test
    void firstCrossReferenceAndFirstAccountWin() {
        List<CardXref> xrefs = List.of(new CardXref("1000000000000001", 1, 1), new CardXref("1000000000000001", 1, 6));
        List<Account> accounts = List.of(account(1, "RETAIL"), account(1, "INSTL"));

        TransferRequested transfer = service.extract(List.of(
                tran("T1", "08", "1000000000000001", "XFER TO ACCT 00000000002", "1.00")),
                xrefs, accounts).requested().get(0);

        assertThat(transfer.sourceAccountId()).isEqualTo(1);
        assertThat(transfer.bookId()).isEqualTo("RETAIL");
    }

    @Test
    void keepsInputOrderAndHasNo500RowTableLimit() {
        List<CardXref> xrefs = new ArrayList<>();
        List<Account> accounts = new ArrayList<>();
        List<DailyTransaction> trans = new ArrayList<>();
        for (int i = 1; i <= 750; i++) {
            xrefs.add(new CardXref(String.format("%016d", i), i, i));
            accounts.add(account(i, "RETAIL"));
        }
        for (int i = 750; i >= 1; i--) {
            trans.add(tran("T" + i, "08", String.format("%016d", i), "XFER TO ACCT 00000000001", "1.00"));
        }

        IntakeResult result = service.extract(trans, xrefs, accounts);

        assertThat(result.rejected()).isEmpty();
        assertThat(result.requested()).hasSize(750);
        assertThat(result.requested().get(0).tranId()).isEqualTo("T750");
        assertThat(result.requested().get(749).tranId()).isEqualTo("T1");
    }

    @Test
    void valuesTheContractCannotHoldAreRefusedNotGuessed() {
        assertThatThrownBy(() -> service.extract(List.of(
                tran("T1", "08", "1000000000000001", "XFER TO ACCT 123", "1.00")), XREFS, ACCOUNTS))
                .isInstanceOf(UnrepresentableTransferException.class)
                .hasMessageContaining("TRAN-DESC(14:11)");
        assertThatThrownBy(() -> service.extract(List.of(
                tran("T1", "08", "1000000000000001", "XFER TO ACCT 00000000002", "1.00", "")), XREFS, ACCOUNTS))
                .isInstanceOf(UnrepresentableTransferException.class)
                .hasMessageContaining("TRAN-ORIG-TS(1:10)");
    }

    @Test
    void counterIsPic9Of9() {
        assertThat(TransferIntakeService.counter(1_234_567_890L)).isEqualTo("234567890");
    }
}
