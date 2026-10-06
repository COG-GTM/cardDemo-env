package com.carddemo.xferfee.posting;

import static com.carddemo.xferfee.posting.PostingFixture.account;
import static com.carddemo.xferfee.posting.PostingFixture.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.AccountPosting.PostingResult;
import com.carddemo.xferfee.contracts.LedgerEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryAccountPostingTest {

    private final InMemoryAccountPosting posting = new InMemoryAccountPosting(
            PostingFixture.SCHEDULE, PostingFixture.POLICY, PostingMode.BATCH_ATOMIC);
    private final List<Account> master = List.of(account(1, "Y", "1000.00", "0.00"), account(2, "Y", "0.00", "0.00"));
    private final LedgerEntry before = new LedgerEntry("T0", LocalDate.of(2024, 6, 1), 1, 2, "INSTL",
            new BigDecimal("10.00"), new BigDecimal("0.05"), false);

    @Test
    void returnsRewrittenMasterLedgerAndStep020Sysout() {
        PostingResult result = posting.post(List.of(transfer("T1", 1, 2, "RETAIL", "100.00"),
                transfer("T2", 2, 1, "INSTL", "10.00")), master, List.of(before));

        assertThat(result.report().step()).isEqualTo("STEP020");
        assertThat(result.report().returnCode()).isZero();
        assertThat(result.report().sysout()).containsExactly(
                "XFERFEE: TRANSFERS POSTED 000000002", "XFERFEE: TOTAL FEES +00000000155");
        assertThat(result.accountMasterAfter()).extracting(Account::currentBalance)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("908.50"), new BigDecimal("89.95"));
        assertThat(result.ledgerAfter()).extracting(LedgerEntry::tranId).containsExactly("T0", "T1", "T2");
    }

    @Test
    void abendReturnsRc8WithNothingCommitted() {
        PostingResult result = posting.post(List.of(transfer("T1", 1, 2, "RETAIL", "100.00"),
                transfer("T0", 1, 2, "RETAIL", "1.00")), master, List.of(before));

        assertThat(result.report().returnCode()).isEqualTo(8);
        assertThat(result.report().sysout()).containsExactly(
                "XFERFEE: LEDGER INSERT FAILED (DUPLICATE TRAN_ID T0)", "XFERFEE: 9999-ABEND-PROGRAM");
        assertThat(result.posted()).isEmpty();
        assertThat(result.ledgerAfter()).containsExactly(before);
        assertThat(result.accountMasterAfter()).isEqualTo(master);
    }
}
