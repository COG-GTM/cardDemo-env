package com.carddemo.xferfee.posting;

import static com.carddemo.xferfee.posting.CobolAccountPostingTest.account;
import static com.carddemo.xferfee.posting.CobolAccountPostingTest.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.RejectReason;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerTransferPostingTest {

    final PerTransferPosting posting = new PerTransferPosting(
            new CobolAccountPosting(CobolAccountPostingTest.SCHEDULE, CobolAccountPostingTest.POLICY));
    final List<Account> master = List.of(account(1, "10.00"), account(2, "500.00"));

    @Test
    void failingTransferIsDeadLetteredAndTheRestPost() {
        AccountPosting.PostingResult result = posting.post(List.of(
                transfer("T1", 1, 2, "RETAIL", "100.00"),
                transfer("T2", 1, 9, "RETAIL", "1.00"),
                transfer("T3", 1, 2, "RETAIL", "10.00")), master, List.of());
        assertThat(result.posted()).extracting(TransferPosted::tranId).containsExactly("T1", "T3");
        assertThat(result.rejected()).extracting(TransferRejected::reason).containsExactly(RejectReason.UNKNOWN_ACCOUNT);
        assertThat(result.report().returnCode()).isEqualTo(4);
        assertThat(result.accountMasterAfter().get(0).currentBalance()).isEqualByComparingTo("-101.65");
    }

    @Test
    void identicalRedeliveryIsANoOpButAConflictingDuplicateIsRejected() {
        AccountPosting.PostingResult result = posting.post(List.of(
                transfer("T1", 1, 2, "RETAIL", "100.00"),
                transfer("T1", 1, 2, "RETAIL", "100.00"),
                transfer("T1", 1, 2, "RETAIL", "99.00")), master, List.of());
        assertThat(result.posted()).hasSize(1);
        assertThat(result.ledgerAfter()).hasSize(1);
        assertThat(result.rejected()).extracting(TransferRejected::reason)
                .containsExactly(RejectReason.DUPLICATE_TRAN_ID);
        assertThat(result.accountMasterAfter().get(1).currentBalance()).isEqualByComparingTo("600.00");
    }
}
