package com.carddemo.xferfee.parity.interim;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.AccountPosting.PostingResult;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.RejectReason;
import com.carddemo.xferfee.contracts.TransferIntake.IntakeResult;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** CBXFR01C and XFERFEE load at most 500 xref/account rows ({@code OCCURS 500}); later rows are invisible. */
class CobolTableLimitTest {

    private static final LocalDate DAY = LocalDate.of(2024, 1, 15);

    private static List<Account> accounts(int n) {
        return LongStream.rangeClosed(1, n).mapToObj(CobolTableLimitTest::account).toList();
    }

    private static Account account(long id) {
        return new Account(id, "Y", new BigDecimal("1000.00"), new BigDecimal("5000.00"), new BigDecimal("1000.00"),
                "2020-01-01", "2030-01-01", "2020-01-01", BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                "12345", "BOOK1");
    }

    private static String card(long n) {
        return String.format("%016d", n);
    }

    private static DailyTransaction transfer(String id, String card, long target) {
        return new DailyTransaction(id, "08", 1, "ONLINE", String.format("TRANSFER TO  %011d", target),
                new BigDecimal("100.00"), 0L, "BANK", "CITY", "12345", card,
                "2024-01-15 10:00:00.000000", "2024-01-15 10:00:00.000000");
    }

    @Test
    void intakeIgnoresXrefsAndAccountsBeyondRow500() {
        List<CardXref> xrefs = LongStream.rangeClosed(1, 501).mapToObj(n -> new CardXref(card(n), n, n)).toList();
        IntakeResult result = new InterimTransferIntake().extract(
                List.of(transfer("T500", card(500), 1), transfer("T501", card(501), 1)), xrefs, accounts(501));
        assertThat(result.requested()).extracting(TransferRequested::tranId).containsExactly("T500");
        assertThat(result.rejected()).singleElement()
                .satisfies(r -> assertThat(r.reason()).isEqualTo(RejectReason.UNMATCHED_CARD));
        assertThat(result.report().returnCode()).isEqualTo(4);
    }

    @Test
    void postingCannotFindAccountsBeyondRow500AndWritesOnlyTheLoadedTable() {
        InterimFeeSchedule schedule = new InterimFeeSchedule();
        schedule.seed(List.of(new FeeRule("BOOK1", new BigDecimal("0.010000"), new BigDecimal("25.00"),
                LocalDate.of(2020, 1, 1), LocalDate.of(9999, 12, 31))));
        AccountPosting posting = new AccountPosting(schedule);

        PostingResult ok = posting.post(List.of(request("T1", 1, 500)), accounts(501), List.of());
        assertThat(ok.report().returnCode()).isZero();
        assertThat(ok.accountMasterAfter()).hasSize(500);

        PostingResult abend = posting.post(List.of(request("T2", 1, 501)), accounts(501), List.of());
        assertThat(abend.report().returnCode()).isGreaterThan(4);
        assertThat(abend.report().sysout()).anyMatch(line -> line.startsWith("XFERFEE: ACCOUNT NOT FOUND"));
    }

    private static TransferRequested request(String id, long source, long target) {
        return new TransferRequested(id, DAY, source, target, "BOOK1", new BigDecimal("100.00"), card(source));
    }

    private record AccountPosting(InterimFeeSchedule schedule) {
        PostingResult post(List<TransferRequested> transfers, List<Account> master, List<com.carddemo.xferfee.contracts.LedgerEntry> ledger) {
            return new InterimAccountPosting(schedule, new InterimFeePolicy()).post(transfers, master, ledger);
        }
    }
}
