package com.carddemo.posting;

import static com.carddemo.posting.PostingFixtures.account;
import static com.carddemo.posting.PostingFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = PostingTestApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:batch-atomic;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "posting.mode=batch-atomic"})
class BatchAtomicPostingTest {

    @Autowired
    PostingRunner runner;
    @Autowired
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
    }

    @Test
    void commitsWholeRun() {
        RunResult result = runner.run(List.of(
                transfer("TRN0000000000031", "2024-06-20", 1, 2, "RETAIL", "100.00"),
                transfer("TRN0000000000032", "2024-06-20", 2, 1, "INSTL", "1000.00")));

        assertThat(runner.mode()).isEqualTo(PostingMode.BATCH_ATOMIC);
        assertThat(result.returnCode()).isZero();
        assertThat(result.committed()).isTrue();
        assertThat(result.feeTotal()).isEqualByComparingTo("6.50");
        assertThat(ledger.findAll()).hasSize(2);
        assertThat(outbox.findAll()).hasSize(2);
    }

    @Test
    void anyFailureRollsBackEverythingWithRc8() {
        RunResult result = runner.run(List.of(
                transfer("TRN0000000000041", "2024-06-20", 1, 2, "RETAIL", "100.00"),
                transfer("TRN0000000000042", "2024-06-20", 1, 99, "RETAIL", "100.00"),
                transfer("TRN0000000000043", "2024-06-20", 2, 1, "INSTL", "1000.00")));

        assertThat(result.returnCode()).isEqualTo(RunResult.RC_ABEND);
        assertThat(result.committed()).isFalse();
        assertThat(result.posted()).hasSize(1);
        assertThat(result.diagnostics()).containsExactly("XFERFEE: ACCOUNT NOT FOUND 00000000001 / 00000000099");
        assertThat(ledger.findAll()).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
        assertThat(accounts.find(1).orElseThrow().currBal()).isEqualByComparingTo("1000.00");
        assertThat(accounts.find(2).orElseThrow().currBal()).isEqualByComparingTo("500.00");
    }
}
