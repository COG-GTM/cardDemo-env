package com.carddemo.xferfee.posting;

import static com.carddemo.xferfee.posting.PostingFixture.account;
import static com.carddemo.xferfee.posting.PostingFixture.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.RejectReason;
import com.carddemo.xferfee.contracts.TransferPosted;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransferPostingRunnerTest {

    private static final List<Account> MASTER = List.of(
            account(1, "Y", "1000.00", "5000.00"),
            account(2, "Y", "50.00", "5000.00"),
            account(3, "Y", "0.00", "5000.00"));

    @Test
    void postsBalancesAndCycleTotalsPerBr11() {
        try (PostingFixture db = new PostingFixture(PostingMode.BATCH_ATOMIC)) {
            db.load(MASTER, List.of());

            PostingRunResult result = db.runner.run(List.of(transfer("T1", 1, 2, "RETAIL", "100.00")));

            assertThat(result.returnCode()).isZero();
            TransferPosted posted = result.posted().get(0);
            assertThat(posted.feeAmount()).isEqualByComparingTo("1.50");
            assertThat(posted.feePct()).isEqualByComparingTo("0.015");
            assertThat(posted.ruleEffectiveDate()).isEqualTo(LocalDate.of(2024, 6, 15));
            Account source = db.account(1);
            Account target = db.account(2);
            assertThat(source.currentBalance()).isEqualByComparingTo("898.50");
            assertThat(source.currentCycleDebit()).isEqualByComparingTo("101.50");
            assertThat(source.currentCycleCredit()).isEqualByComparingTo("0.00");
            assertThat(target.currentBalance()).isEqualByComparingTo("150.00");
            assertThat(target.currentCycleCredit()).isEqualByComparingTo("100.00");
            assertThat(target.currentCycleDebit()).isEqualByComparingTo("0.00");
        }
    }

    @Test
    void writesLedgerRowAndTransferPostedOutboxEventInTheSameTransaction() {
        try (PostingFixture db = new PostingFixture(PostingMode.BATCH_ATOMIC)) {
            db.load(MASTER, List.of());

            db.runner.run(List.of(transfer("T1", 1, 2, "INSTL", "200000.00")));

            assertThat(db.ledger.findAll()).containsExactly(new LedgerEntry("T1", LocalDate.of(2024, 6, 20),
                    1, 2, "INSTL", new BigDecimal("200000.00"), new BigDecimal("500.00"), true));
            assertThat(db.outbox.findAll()).singleElement().satisfies(event -> {
                assertThat(event.eventType()).isEqualTo(OutboxRepository.TRANSFER_POSTED);
                assertThat(event.aggregateId()).isEqualTo("T1");
                assertThat(event.payload()).contains("\"feeAmount\":\"500.00\"", "\"capApplied\":true",
                        "\"tranDate\":\"2024-06-20\"");
            });
        }
    }

    @Test
    void makesNoFundsStatusOrLimitChecks() {
        List<Account> master = List.of(account(1, "N", "10.00", "0.00"), account(2, "N", "0.00", "0.00"));
        try (PostingFixture db = new PostingFixture(PostingMode.BATCH_ATOMIC)) {
            db.load(master, List.of());

            PostingRunResult result = db.runner.run(List.of(transfer("T1", 1, 2, "INSTL", "100000.00")));

            assertThat(result.returnCode()).isZero();
            assertThat(db.account(1).currentBalance()).isEqualByComparingTo("-100490.00");
        }
    }

    @Test
    void sameSourceAndTargetNetsToTheFee() {
        try (PostingFixture db = new PostingFixture(PostingMode.BATCH_ATOMIC)) {
            db.load(MASTER, List.of());

            db.runner.run(List.of(transfer("T1", 1, 1, "RETAIL", "100.00")));

            Account account = db.account(1);
            assertThat(account.currentBalance()).isEqualByComparingTo("998.50");
            assertThat(account.currentCycleCredit()).isEqualByComparingTo("100.00");
            assertThat(account.currentCycleDebit()).isEqualByComparingTo("101.50");
        }
    }

    @Test
    void locksAccountsInAscendingIdOrder() {
        try (PostingFixture db = new PostingFixture(PostingMode.BATCH_ATOMIC)) {
            db.load(MASTER, List.of());

            assertThat(db.accounts.lockForUpdate(List.of(3L, 1L, 3L)).keySet()).containsExactly(1L, 3L);
        }
    }

    @Test
    void postsInInputOrderAndKeepsMasterOrder() {
        try (PostingFixture db = new PostingFixture(PostingMode.BATCH_ATOMIC)) {
            db.load(List.of(account(3, "Y", "0.00", "0.00"), account(1, "Y", "0.00", "0.00")), List.of());

            PostingRunResult result = db.runner.run(List.of(
                    transfer("T9", 3, 1, "INSTL", "10.00"), transfer("T1", 1, 3, "INSTL", "20.00")));

            assertThat(result.posted()).extracting(TransferPosted::tranId).containsExactly("T9", "T1");
            assertThat(db.master.rewrittenMaster()).extracting(Account::accountId).containsExactly(3L, 1L);
        }
    }

    @Test
    void batchAtomicRollsBackEverythingOnMissingAccount() {
        try (PostingFixture db = new PostingFixture(PostingMode.BATCH_ATOMIC)) {
            db.load(MASTER, List.of());

            PostingRunResult result = db.runner.run(List.of(
                    transfer("T1", 1, 2, "RETAIL", "100.00"), transfer("T2", 1, 99, "RETAIL", "5.00")));

            assertThat(result.returnCode()).isEqualTo(8);
            assertThat(result.posted()).isEmpty();
            assertThat(result.abendMessage()).contains("ACCOUNT NOT FOUND");
            assertThat(db.master.rewrittenMaster()).isEqualTo(MASTER);
            assertThat(db.ledger.findAll()).isEmpty();
            assertThat(db.outbox.findAll()).isEmpty();
        }
    }

    @Test
    void batchAtomicAbendsOnDuplicateTranId() {
        LedgerEntry existing = new LedgerEntry("T2", LocalDate.of(2024, 6, 1), 1, 2, "RETAIL",
                new BigDecimal("1.00"), new BigDecimal("0.02"), false);
        try (PostingFixture db = new PostingFixture(PostingMode.BATCH_ATOMIC)) {
            db.load(MASTER, List.of(existing));

            PostingRunResult result = db.runner.run(List.of(
                    transfer("T1", 1, 2, "RETAIL", "100.00"), transfer("T2", 2, 3, "RETAIL", "5.00")));

            assertThat(result.returnCode()).isEqualTo(8);
            assertThat(result.abendMessage()).contains("DUPLICATE TRAN_ID T2");
            assertThat(db.ledger.findAll()).containsExactly(existing);
            assertThat(db.master.rewrittenMaster()).isEqualTo(MASTER);
        }
    }

    @Test
    void batchAtomicAbendsWhenNoFeeRuleIsEffective() {
        try (PostingFixture db = new PostingFixture(PostingMode.BATCH_ATOMIC)) {
            db.load(MASTER, List.of());

            PostingRunResult result = db.runner.run(List.of(transfer("T1", 1, 2, "PROMO", "100.00")));

            assertThat(result.returnCode()).isEqualTo(8);
            assertThat(result.abendMessage()).isEqualTo("XFERFEE: NO FEE RULE FOR BOOK PROMO     ");
        }
    }

    @Test
    void perTransferCommitsGoodTransfersAndParksFailures() {
        try (PostingFixture db = new PostingFixture(PostingMode.PER_TRANSFER)) {
            db.load(MASTER, List.of());

            PostingRunResult result = db.runner.run(List.of(
                    transfer("T1", 1, 99, "RETAIL", "5.00"), transfer("T2", 1, 2, "RETAIL", "100.00")));

            assertThat(result.returnCode()).isEqualTo(4);
            assertThat(result.posted()).extracting(TransferPosted::tranId).containsExactly("T2");
            assertThat(result.rejected()).singleElement().satisfies(r -> {
                assertThat(r.tranId()).isEqualTo("T1");
                assertThat(r.reason()).isEqualTo(RejectReason.UNKNOWN_ACCOUNT);
                assertThat(r.cardNumber()).isEqualTo("1000000000000001");
            });
            assertThat(db.account(1).currentBalance()).isEqualByComparingTo("898.50");
            assertThat(db.ledger.findAll()).extracting(LedgerEntry::tranId).containsExactly("T2");
            assertThat(db.outbox.findAll()).extracting(OutboxEvent::eventType)
                    .containsExactly(OutboxRepository.TRANSFER_REJECTED, OutboxRepository.TRANSFER_POSTED);
        }
    }
}
