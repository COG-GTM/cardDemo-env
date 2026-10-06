package com.carddemo.xferfee.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.feepolicy.HalfUpFeePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

class FeePricingReplayTest {

    @TempDir
    Path work;

    private static final String RULES = """
            {"bookId":"RETAIL","feePct":"0.012500","feeCap":"25.00","effectiveDate":"2020-01-01","expiryDate":"2024-06-15"}
            {"bookId":"RETAIL","feePct":"0.015000","feeCap":"25.00","effectiveDate":"2024-06-15","expiryDate":"9999-12-31"}
            """;

    @Test
    void feeFieldsComeFromTheFeePolicyBean() throws Exception {
        writeInput(RULES, """
                {"tranId":"T1","tranDate":"2024-06-14","sourceAccountId":1,"targetAccountId":2,"bookId":"RETAIL","amount":"2.00","cardNumber":"4000000000000001"}
                {"tranId":"T2","tranDate":"2024-06-15","sourceAccountId":1,"targetAccountId":2,"bookId":"RETAIL","amount":"2000.00","cardNumber":"4000000000000001"}
                {"tranId":"T3","tranDate":"2024-06-15","sourceAccountId":1,"targetAccountId":2,"bookId":"RETAIL","amount":"0.00","cardNumber":"4000000000000001"}
                """);

        try (ConfigurableApplicationContext context = run()) {
            assertThat(context.getBean(FeePolicy.class)).isInstanceOf(HalfUpFeePolicy.class);
        }

        assertThat(Files.readAllLines(work.resolve("out/datasets/AWS.M2.CARDDEMO.XFER.FEES.jsonl")))
                .satisfiesExactly(
                        t1 -> assertThat(t1).contains("\"feePct\":\"0.012500\"", "\"feeAmount\":\"0.03\"",
                                "\"capApplied\":false", "\"ruleEffectiveDate\":\"2020-01-01\""),
                        t2 -> assertThat(t2).contains("\"feeAmount\":\"25.00\"", "\"capApplied\":true",
                                "\"ruleEffectiveDate\":\"2024-06-15\""),
                        t3 -> assertThat(t3).contains("\"feeAmount\":\"0.00\"", "\"capApplied\":false"));
        assertThat(Files.readAllLines(work.resolve("out/db2_after/XFER_FEE_LEDGER.jsonl"))).hasSize(3);
        assertThat(Files.readString(work.resolve("out/rc.json"))).contains("\"STEP020\":0");
    }

    @Test
    void missingRuleStopsTheRunWithoutCommitting() throws Exception {
        writeInput(RULES, """
                {"tranId":"T1","tranDate":"2024-06-14","sourceAccountId":1,"targetAccountId":2,"bookId":"RETAIL","amount":"2.00","cardNumber":"4000000000000001"}
                {"tranId":"T2","tranDate":"2024-06-14","sourceAccountId":1,"targetAccountId":2,"bookId":"NOBOOK","amount":"0.00","cardNumber":"4000000000000001"}
                """);

        run().close();

        assertThat(work.resolve("out/datasets/AWS.M2.CARDDEMO.XFER.FEES.jsonl")).doesNotExist();
        assertThat(Files.readAllLines(work.resolve("out/db2_after/XFER_FEE_LEDGER.jsonl"))).isEmpty();
        assertThat(Files.readString(work.resolve("out/rc.json"))).contains("\"STEP020\":8");
    }

    private void writeInput(String rules, String extract) throws Exception {
        Files.createDirectories(work.resolve("in/db2_before"));
        Files.createDirectories(work.resolve("in/recorded"));
        Files.writeString(work.resolve("in/db2_before/CTL_XFER_PARM.jsonl"), rules);
        Files.writeString(work.resolve("in/recorded/XFER.EXTRACT.jsonl"), extract);
    }

    private ConfigurableApplicationContext run() {
        return SpringApplication.run(ParityReplayApplication.class,
                "--in=" + work.resolve("in"), "--out=" + work.resolve("out"));
    }
}
