package com.carddemo.posting;

import static com.carddemo.posting.PostingFixtures.account;
import static com.carddemo.posting.PostingFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;

import com.carddemo.contracts.Account;
import com.carddemo.contracts.TransferPosted;
import com.carddemo.posting.PostingException.Reason;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = PostingTestApplication.class,
        properties = "spring.datasource.url=jdbc:h2:mem:per-transfer;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class PostingServiceTest {

    @Autowired
    PostingService service;
    @Autowired
    PostingRunner runner;
    @SpyBean
    AccountRepository accounts;
    @Autowired
    FeeLedgerRepository ledger;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        PostingFixtures.reset(jdbc);
        accounts.insert(account(1, "1000.00"));
        accounts.insert(account(2, "500.00"));
        accounts.insert(account(3, "800.00"));
    }

    @Test
    void defaultsToPerTransferMode() {
        assertThat(runner.mode()).isEqualTo(PostingMode.PER_TRANSFER);
    }

    @Test
    void postsBalancesCycleTotalsLedgerAndOutbox() {
        TransferPosted posted = service.post(transfer("TRN0000000000002", "2024-06-20", 1, 2, "RETAIL", "100.00"));

        assertThat(posted.feeAmount()).isEqualByComparingTo("1.50");
        assertThat(posted.feePct()).isEqualByComparingTo("0.015");
        assertThat(posted.ruleEffectiveDate()).isEqualTo(LocalDate.parse("2024-06-15"));
        assertBalances(1, "898.50", "0.00", "101.50");
        assertBalances(2, "600.00", "100.00", "0.00");

        List<Map<String, Object>> rows = ledger.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("TRAN_ID")).isEqualTo("TRN0000000000002");
        assertThat((BigDecimal) rows.get(0).get("FEE_AMT")).isEqualByComparingTo("1.50");
        assertThat(rows.get(0).get("CAP_APPLIED")).isEqualTo("N");

        List<Map<String, Object>> events = outbox.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("EVENT_TYPE")).isEqualTo("TransferPosted");
        assertThat(events.get(0).get("AGGREGATE_ID")).isEqualTo("TRN0000000000002");
        assertThat((String) events.get(0).get("PAYLOAD"))
                .contains("\"feeAmount\":\"1.50\"", "\"capApplied\":\"N\"", "\"amount\":\"100.00\"");
    }

    @Test
    void sameSourceAndTargetAppliesBothSides() {
        service.post(transfer("TRN0000000000009", "2024-06-05", 3, 3, "RETAIL", "50.00"));

        assertBalances(3, "799.37", "50.00", "50.63");
    }

    @Test
    void capIsAppliedOnlyWhenStrictlyGreater() {
        TransferPosted atCap = service.post(transfer("TRN0000000000010", "2024-06-05", 1, 2, "RETAIL", "2000.00"));
        TransferPosted overCap = service.post(transfer("TRN0000000000011", "2024-06-05", 1, 2, "RETAIL", "2000.80"));

        assertThat(atCap.feeAmount()).isEqualByComparingTo("25.00");
        assertThat(atCap.capApplied()).isFalse();
        assertThat(overCap.feeAmount()).isEqualByComparingTo("25.00");
        assertThat(overCap.capApplied()).isTrue();
    }

    @Test
    void noFundsStatusOrLimitChecks() {
        jdbc.update("UPDATE ACCOUNT SET ACTIVE_STATUS = 'N', CREDIT_LIMIT = 0 WHERE ACCT_ID = 2");

        service.post(transfer("TRN0000000000012", "2024-06-20", 2, 1, "INSTL", "100000.00"));

        assertBalances(2, "-100000.00", "0.00", "100500.00");
    }

    @Test
    void zeroAmountStillNeedsRuleAndWritesLedger() {
        TransferPosted posted = service.post(transfer("TRN0000000000013", "2024-06-20", 1, 2, "RETAIL", "0.00"));

        assertThat(posted.feeAmount()).isEqualByComparingTo("0.00");
        assertThat(posted.capApplied()).isFalse();
        assertThat(ledger.findAll()).hasSize(1);
        assertBalances(1, "1000.00", "0.00", "0.00");
    }

    @Test
    void missingAccountRollsBackAndWritesNothing() {
        assertThatThrownBy(() -> service.post(transfer("TRN0000000000014", "2024-06-20", 1, 99, "RETAIL", "10.00")))
                .isInstanceOfSatisfying(PostingException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.ACCOUNT_NOT_FOUND);
                    assertThat(e.legacyMessage()).isEqualTo("XFERFEE: ACCOUNT NOT FOUND 00000000001 / 00000000099");
                });

        assertBalances(1, "1000.00", "0.00", "0.00");
        assertThat(ledger.findAll()).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void missingFeeRuleIsRejected() {
        assertThatThrownBy(() -> service.post(transfer("TRN0000000000015", "2024-06-20", 1, 2, "CORP", "10.00")))
                .isInstanceOfSatisfying(PostingException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.NO_FEE_RULE);
                    assertThat(e.legacyMessage()).isEqualTo("XFERFEE: NO FEE RULE FOR BOOK CORP      ");
                });
        assertBalances(1, "1000.00", "0.00", "0.00");
    }

    @Test
    void duplicateTranIdIsRejectedAndRolledBack() {
        service.post(transfer("TRN0000000000016", "2024-06-20", 1, 2, "RETAIL", "100.00"));

        assertThatThrownBy(() -> service.post(transfer("TRN0000000000016", "2024-06-21", 1, 2, "RETAIL", "100.00")))
                .isInstanceOfSatisfying(PostingException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.LEDGER_INSERT_FAILED));

        assertBalances(1, "898.50", "0.00", "101.50");
        assertThat(ledger.findAll()).hasSize(1);
        assertThat(outbox.findAll()).hasSize(1);
    }

    @Test
    void locksAccountsInIdOrder() {
        service.post(transfer("TRN0000000000017", "2024-06-20", 3, 1, "RETAIL", "10.00"));

        InOrder order = inOrder(accounts);
        order.verify(accounts).lock(1L);
        order.verify(accounts).lock(3L);
    }

    @Test
    void perTransferRunRejectsFailuresAndContinues() {
        RunResult result = runner.run(List.of(
                transfer("TRN0000000000021", "2024-06-20", 1, 2, "RETAIL", "100.00"),
                transfer("TRN0000000000022", "2024-06-20", 1, 99, "RETAIL", "100.00"),
                transfer("TRN0000000000023", "2024-06-20", 2, 3, "INSTL", "1000.00")));

        assertThat(result.returnCode()).isEqualTo(RunResult.RC_REJECTS);
        assertThat(result.posted()).extracting(TransferPosted::tranId)
                .containsExactly("TRN0000000000021", "TRN0000000000023");
        assertThat(result.rejected()).singleElement()
                .satisfies(r -> assertThat(r.reasonCode()).isEqualTo("ACCOUNT_NOT_FOUND"));
        assertThat(ledger.findAll()).hasSize(2);
        assertThat(outbox.findAll()).extracting(row -> row.get("EVENT_TYPE"))
                .containsExactly("TransferPosted", "TransferRejected", "TransferPosted");
    }

    private void assertBalances(long id, String balance, String cycleCredit, String cycleDebit) {
        Account account = accounts.find(id).orElseThrow();
        assertThat(account.currBal()).as("balance of %d", id).isEqualByComparingTo(balance);
        assertThat(account.currCycCredit()).as("cycle credit of %d", id).isEqualByComparingTo(cycleCredit);
        assertThat(account.currCycDebit()).as("cycle debit of %d", id).isEqualByComparingTo(cycleDebit);
    }
}
