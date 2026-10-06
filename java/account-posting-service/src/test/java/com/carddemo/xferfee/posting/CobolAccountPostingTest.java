package com.carddemo.xferfee.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.RejectReason;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CobolAccountPostingTest {

    static final LocalDate DAY = LocalDate.parse("2024-06-20");

    static final FeeSchedule SCHEDULE = new FeeSchedule() {
        private final List<FeeRule> rules = new ArrayList<>(List.of(new FeeRule("RETAIL",
                new BigDecimal("0.015000"), new BigDecimal("25.00"), LocalDate.parse("2020-01-01"),
                LocalDate.parse("9999-12-31"))));

        @Override
        public void seed(List<FeeRule> newRules) {
            rules.clear();
            rules.addAll(newRules);
        }

        @Override
        public Optional<FeeRule> effectiveRule(String bookId, LocalDate date) {
            return rules.stream().filter(r -> r.bookId().equals(bookId)).findFirst();
        }

        @Override
        public List<FeeRule> rules() {
            return rules;
        }
    };

    static final FeePolicy POLICY = (amount, rule) -> {
        BigDecimal fee = amount.multiply(rule.feePct()).setScale(2, RoundingMode.HALF_UP);
        return fee.compareTo(rule.feeCap()) > 0 ? new FeeResult(rule.feeCap(), true) : new FeeResult(fee, false);
    };

    static Account account(long id, String balance) {
        BigDecimal z = new BigDecimal("0.00");
        return new Account(id, "Y", new BigDecimal(balance), z, z, "2020-01-01", "2030-12-31", "2025-01-01", z, z,
                "00001", "RETAIL");
    }

    static TransferRequested transfer(String id, long src, long tgt, String book, String amount) {
        return new TransferRequested(id, DAY, src, tgt, book, new BigDecimal(amount), "1000000000000001");
    }

    final AccountPosting posting = new CobolAccountPosting(SCHEDULE, POLICY);
    final List<Account> master = List.of(account(1, "10.00"), account(2, "500.00"));

    @Test
    void postsWithoutFundsChecksAndUpdatesCycles() {
        AccountPosting.PostingResult result = posting.post(List.of(transfer("T1", 1, 2, "RETAIL", "100.00")),
                master, List.of());
        Account source = result.accountMasterAfter().get(0);
        Account target = result.accountMasterAfter().get(1);
        assertThat(source.currentBalance()).isEqualByComparingTo("-91.50");
        assertThat(source.currentCycleDebit()).isEqualByComparingTo("101.50");
        assertThat(target.currentBalance()).isEqualByComparingTo("600.00");
        assertThat(target.currentCycleCredit()).isEqualByComparingTo("100.00");
        assertThat(result.ledgerAfter()).singleElement()
                .satisfies(e -> assertThat(e.feeAmount()).isEqualByComparingTo("1.50"));
        assertThat(result.report().sysout()).containsExactly("XFERFEE: TRANSFERS POSTED 000000001",
                "XFERFEE: TOTAL FEES +00000000150");
    }

    @Test
    void missingRuleAbendsWholeBatchWithRc8() {
        AccountPosting.PostingResult result = posting.post(List.of(transfer("T1", 1, 2, "RETAIL", "1.00"),
                transfer("T2", 1, 2, "INSTL", "1.00")), master, List.of());
        assertThat(result.report().returnCode()).isEqualTo(8);
        assertThat(result.posted()).isEmpty();
        assertThat(result.accountMasterAfter()).isEqualTo(master);
        assertThat(result.report().sysout()).first().isEqualTo("XFERFEE: NO FEE RULE FOR BOOK INSTL     ");
        assertThat(result.rejected()).singleElement()
                .satisfies(r -> assertThat(r.reason()).isEqualTo(RejectReason.NO_FEE_RULE));
    }

    @Test
    void missingTargetAccountAbends() {
        AccountPosting.PostingResult result = posting.post(List.of(transfer("T1", 1, 9, "RETAIL", "1.00")),
                master, List.of());
        assertThat(result.report().returnCode()).isEqualTo(8);
        assertThat(result.report().sysout()).first()
                .isEqualTo("XFERFEE: ACCOUNT NOT FOUND 00000000001 / 00000000009");
    }

    @Test
    void duplicateTranIdAbendsWithSqlcode403() {
        List<LedgerEntry> ledger = List.of(new LedgerEntry("T1", DAY, 1, 2, "RETAIL", new BigDecimal("1.00"),
                new BigDecimal("0.02"), false));
        AccountPosting.PostingResult result = posting.post(List.of(transfer("T1", 1, 2, "RETAIL", "1.00")),
                master, ledger);
        assertThat(result.report().returnCode()).isEqualTo(8);
        assertThat(result.ledgerAfter()).isEqualTo(ledger);
        assertThat(result.report().sysout()).containsExactly(
                "XFERFEE: LEDGER INSERT FAILED -0000000403",
                "XFERFEE: 9999-ABEND-PROGRAM",
                "libcob: warning: implicit CLOSE of XFERFEE ('XFERFEE')",
                "libcob: warning: implicit CLOSE of ACCTOUT ('ACCTOUT')",
                "libcob: warning: implicit CLOSE of XFEREXTR ('XFEREXTR')",
                "libcob: warning: implicit CLOSE of ACCTFILE ('ACCTFILE')");
    }

    @Test
    void signedDisplayMatchesGnuCobol() {
        assertThat(CobolAccountPosting.signedDisplay(new BigDecimal("6.50"), 9, 2)).isEqualTo("+00000000650");
        assertThat(CobolAccountPosting.signedDisplay(new BigDecimal("-1.5"), 9, 2)).isEqualTo("-00000000150");
    }
}
