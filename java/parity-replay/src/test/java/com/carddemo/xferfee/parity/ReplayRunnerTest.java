package com.carddemo.xferfee.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Drives the parity-replay CLI over synthetic fixtures for the RC paths the recorded cases lack. */
class ReplayRunnerTest {

    private static final String DAILY = """
            {"tranId":"T1","typeCode":"08","categoryCode":1,"source":"DEMO","description":"XFER TO ACCT 00000000002","amount":"100.00","merchantId":1,"merchantName":"","merchantCity":"","merchantZip":"","cardNumber":"1000000000000001","originTimestamp":"2024-06-10 09:00:00.000000","processTimestamp":"2024-06-10 09:01:00.000000"}
            {"tranId":"T2","typeCode":"08","categoryCode":1,"source":"DEMO","description":"XFER TO ACCT 00000000001","amount":"50.00","merchantId":1,"merchantName":"","merchantCity":"","merchantZip":"","cardNumber":"9999999999999999","originTimestamp":"2024-06-10 09:00:00.000000","processTimestamp":"2024-06-10 09:01:00.000000"}
            {"tranId":"T3","typeCode":"01","categoryCode":1,"source":"DEMO","description":"POS purchase","amount":"42","merchantId":1,"merchantName":"","merchantCity":"","merchantZip":"","cardNumber":"1000000000000001","originTimestamp":"2024-06-10 09:00:00.000000","processTimestamp":"2024-06-10 09:01:00.000000"}
            """;
    private static final String XREF = """
            {"cardNumber":"1000000000000001","customerId":900000001,"accountId":1}
            """;
    private static final String ACCOUNTS = """
            {"accountId":1,"activeStatus":"Y","currentBalance":"1000","creditLimit":"5000","cashCreditLimit":"100","openDate":"2020-01-01","expirationDate":"2030-12-31","reissueDate":"2025-01-01","currentCycleCredit":"0","currentCycleDebit":"0","addressZip":"00001","groupId":"RETAIL"}
            {"accountId":2,"activeStatus":"Y","currentBalance":"0","creditLimit":"5000","cashCreditLimit":"100","openDate":"2020-01-01","expirationDate":"2030-12-31","reissueDate":"2025-01-01","currentCycleCredit":"0","currentCycleDebit":"0","addressZip":"00002","groupId":"RETAIL"}
            """;
    private static final String LEDGER = "tran_id,tran_dt,src_acct_id,tgt_acct_id,book_id,tran_amt,fee_amt,cap_applied\n";

    @TempDir
    Path root;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unmatchedCardIsRc4WarningWithTransferRejectedAndNoAlert() throws Exception {
        Path out = replay("RETAIL    ,0.012500,25.00,2020-01-01,9999-12-31\n");

        assertThat(Files.readAllLines(out.resolve("sysout/STEP010.txt"))).containsExactly(
                "CBXFR01C: CARD NOT FOUND 9999999999999999",
                "CBXFR01C: RECORDS READ 000000003",
                "CBXFR01C: TRANSFERS SELECTED 000000001",
                "CBXFR01C: UNMATCHED CARDS 000000001");
        assertThat(Files.readAllLines(out.resolve("sysout/STEP020.txt"))).containsExactly(
                "XFERFEE: TRANSFERS POSTED 000000001",
                "XFERFEE: TOTAL FEES +00000000125");
        JsonNode step010 = mapper.readTree(out.resolve("sysout/STEP010.json").toFile());
        assertThat(step010.get("returnCode").asInt()).isEqualTo(4);
        assertThat(step010.get("severity").asText()).isEqualTo("WARNING");
        assertThat(step010.get("rejected").get(0).get("reason").asText()).isEqualTo("UNMATCHED_CARD");
        assertThat(mapper.readTree(out.resolve("rc.json").toFile()).toString())
                .isEqualTo("{\"steps\":{\"STEP010\":4,\"STEP020\":0,\"STEP030\":0},\"maxcc\":4}");
        assertThat(Files.readString(out.resolve("observability/alerts.jsonl"))).isEmpty();
        assertThat(Files.readString(out.resolve("observability/dlq.jsonl"))).isEmpty();
        JsonNode metrics = mapper.readTree(out.resolve("observability/metrics.json").toFile());
        assertThat(meter(metrics, "carddemo.xferfee.transfers.rejected").get("tags").get("reason").asText())
                .isEqualTo("UNMATCHED_CARD");
        assertThat(meter(metrics, "carddemo.xferfee.step.warnings").get("count").asDouble()).isEqualTo(1.0);
    }

    @Test
    void missingFeeRuleIsRc8AlertWithDlqEntryAndSkipsStep030() throws Exception {
        Path out = replay("INSTL     ,0.005000,500.00,2020-01-01,9999-12-31\n");

        assertThat(Files.readAllLines(out.resolve("sysout/STEP020.txt"))).containsExactly(
                "XFERFEE: NO FEE RULE FOR BOOK RETAIL    ",
                "XFERFEE: 9999-ABEND-PROGRAM");
        assertThat(out.resolve("sysout/STEP030.txt")).doesNotExist();
        JsonNode step020 = mapper.readTree(out.resolve("sysout/STEP020.json").toFile());
        assertThat(step020.get("severity").asText()).isEqualTo("ALERT");
        assertThat(step020.get("counters")).isEmpty();
        assertThat(mapper.readTree(out.resolve("rc.json").toFile()).get("maxcc").asInt()).isEqualTo(8);
        List<String> alerts = Files.readAllLines(out.resolve("observability/alerts.jsonl"));
        assertThat(alerts).singleElement().satisfies(line -> assertThat(line).contains("\"returnCode\":8"));
        List<String> dlq = Files.readAllLines(out.resolve("observability/dlq.jsonl"));
        assertThat(dlq).singleElement().satisfies(line -> {
            JsonNode entry = mapper.readTree(line);
            assertThat(entry.get("step").asText()).isEqualTo("STEP020");
            assertThat(entry.get("reason").asText()).isEqualTo("NO_FEE_RULE");
            assertThat(entry.get("rejected").get(0).get("tranId").asText()).isEqualTo("T1");
        });
    }

    private Path replay(String feeRules) throws Exception {
        Path fixtures = root.resolve("fixtures");
        Path caseDir = fixtures.resolve("synthetic");
        Files.createDirectories(caseDir.resolve("db2_before"));
        Files.writeString(caseDir.resolve("case.json"), "{\"run_date\":\"2024-06-30\",\"outputs\":[],\"db2\":[]}");
        Files.writeString(caseDir.resolve("db2_before/CTL_XFER_PARM.csv"),
                "book_id,fee_pct,fee_cap,eff_dt,exp_dt\n" + feeRules);
        Files.writeString(caseDir.resolve("db2_before/XFER_FEE_LEDGER.csv"), LEDGER);
        Path input = root.resolve("input");
        Files.createDirectories(input);
        Files.writeString(input.resolve(FixtureInputs.DALYTRAN + ".jsonl"), DAILY);
        Files.writeString(input.resolve(FixtureInputs.CARDXREF + ".jsonl"), XREF);
        Files.writeString(input.resolve(FixtureInputs.ACCTDATA + ".jsonl"), ACCOUNTS);
        Path out = root.resolve("out");
        try (ConfigurableApplicationContext context = SpringApplication.run(ParityReplayApplication.class,
                "--case", "synthetic", "--out", out.toString(), "--input", input.toString(),
                "--fixtures", fixtures.toString())) {
            assertThat(context.isActive()).isTrue();
        }
        return out;
    }

    private static JsonNode meter(JsonNode metrics, String name) {
        for (JsonNode meter : metrics) {
            if (meter.get("name").asText().equals(name)) {
                return meter;
            }
        }
        throw new AssertionError("no meter " + name);
    }
}
