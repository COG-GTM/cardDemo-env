package com.carddemo.xferfee.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.RejectReason;
import com.carddemo.xferfee.contracts.TransferIntake;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CobolTransferIntakeTest {

    private final CobolTransferIntake intake = new CobolTransferIntake();

    static DailyTransaction tran(String id, String type, String card, String desc, String amount) {
        return new DailyTransaction(id, type, 1, "DEMO", desc, new BigDecimal(amount), 1, "M", "C", "00000", card,
                "2024-06-20 12:00:00.000000", "2024-06-20 12:01:00.000000");
    }

    static Account account(long id, String group) {
        BigDecimal z = new BigDecimal("0.00");
        return new Account(id, "Y", new BigDecimal("1000.00"), z, z, "2020-01-01", "2030-12-31", "2025-01-01", z, z,
                "00001", group);
    }

    private final List<CardXref> xrefs = List.of(new CardXref("1000000000000001", 1, 1));
    private final List<Account> accounts = List.of(account(1, "RETAIL"), account(2, "RETAIL"));

    @Test
    void selectsOnlyType08AndResolvesSourceBookTargetAndDate() {
        TransferIntake.IntakeResult result = intake.extract(List.of(
                tran("T1", "01", "1000000000000001", "POS purchase", "42.00"),
                tran("T2", "08", "1000000000000001", "XFER TO ACCT 00000000002", "100.00")), xrefs, accounts);
        assertThat(result.requested()).containsExactly(new TransferRequested("T2", LocalDate.parse("2024-06-20"),
                1, 2, "RETAIL", new BigDecimal("100.00"), "1000000000000001"));
        assertThat(result.report().returnCode()).isZero();
        assertThat(result.report().sysout()).containsExactly(
                "CBXFR01C: RECORDS READ 000000002",
                "CBXFR01C: TRANSFERS SELECTED 000000001",
                "CBXFR01C: UNMATCHED CARDS 000000000");
    }

    @Test
    void unmatchedCardIsSkippedWithRc4AndTheRunContinues() {
        TransferIntake.IntakeResult result = intake.extract(List.of(
                tran("T1", "08", "9999999999999999", "XFER TO ACCT 00000000002", "5.00"),
                tran("T2", "08", "1000000000000001", "XFER TO ACCT 00000000002", "7.00")), xrefs, accounts);
        assertThat(result.requested()).extracting(TransferRequested::tranId).containsExactly("T2");
        assertThat(result.rejected()).singleElement()
                .satisfies(r -> assertThat(r.reason()).isEqualTo(RejectReason.UNMATCHED_CARD));
        assertThat(result.report().returnCode()).isEqualTo(4);
        assertThat(result.report().sysout()).first().isEqualTo("CBXFR01C: CARD NOT FOUND 9999999999999999");
    }

    @Test
    void targetAccountIsDescriptionColumns14To24() {
        assertThat(CobolTransferIntake.targetAccount("XFER TO ACCT 00000000007 trailing")).isEqualTo(7);
        assertThat(CobolTransferIntake.targetAccount("short")).isZero();
    }
}
