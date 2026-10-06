package com.carddemo.xferfee.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferIntake;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayEngineTest {

    private static final Path FIXTURES = Path.of("..", "..", "fixtures", "xferfee");

    @TempDir
    Path work;

    private ReplayOptions options;

    @BeforeEach
    void stageInputs() throws IOException {
        Path input = work.resolve("input");
        JsonLines.write(input.resolve("DALYTRAN.jsonl"), List.of(Map.ofEntries(
                Map.entry("TRAN-ID", "TRN0000000000002"), Map.entry("TRAN-TYPE-CD", "08"),
                Map.entry("TRAN-CAT-CD", "1"), Map.entry("TRAN-SOURCE", "ONLINE"),
                Map.entry("TRAN-DESC", "XFER TO ACCT 00000000002"), Map.entry("TRAN-AMT", "100.00"),
                Map.entry("TRAN-MERCHANT-ID", "0"), Map.entry("TRAN-MERCHANT-NAME", ""),
                Map.entry("TRAN-MERCHANT-CITY", ""), Map.entry("TRAN-MERCHANT-ZIP", ""),
                Map.entry("TRAN-CARD-NUM", "4000000000000001"),
                Map.entry("TRAN-ORIG-TS", "2024-06-20-10.00.00.000000"),
                Map.entry("TRAN-PROC-TS", "2024-06-20-10.00.00.000000"))));
        JsonLines.write(input.resolve("CARDXREF.jsonl"), List.of(Map.of(
                "XREF-CARD-NUM", "4000000000000001", "XREF-CUST-ID", "1", "XREF-ACCT-ID", "1")));
        JsonLines.write(input.resolve("ACCTDATA.jsonl"), List.of(Map.ofEntries(
                Map.entry("ACCT-ID", "1"), Map.entry("ACCT-ACTIVE-STATUS", "Y"),
                Map.entry("ACCT-CURR-BAL", "500.00"), Map.entry("ACCT-CREDIT-LIMIT", "1000.00"),
                Map.entry("ACCT-CASH-CREDIT-LIMIT", "100.00"), Map.entry("ACCT-OPEN-DATE", "2020-01-01"),
                Map.entry("ACCT-EXPIRAION-DATE", "2030-01-01"), Map.entry("ACCT-REISSUE-DATE", "2025-01-01"),
                Map.entry("ACCT-CURR-CYC-CREDIT", "0.00"), Map.entry("ACCT-CURR-CYC-DEBIT", "0.00"),
                Map.entry("ACCT-ADDR-ZIP", "10001"), Map.entry("ACCT-GROUP-ID", "RETAIL"))));
        JsonLines.write(input.resolve("stub").resolve(LegacyRecords.FEES_DSN + ".jsonl"),
                List.of(LegacyRecords.feeRow(posted())));
        options = ReplayOptions.parse(List.of(
                "--case", "default", "--out", work.resolve("out").toString(),
                "--fixtures", FIXTURES.toString()));
    }

    @Test
    void unimplementedModulesWriteNothingButRc() throws IOException {
        Map<String, Integer> steps = engine(null, null, null).run(options);

        Path out = work.resolve("out");
        assertThat(steps).isEmpty();
        assertThat(Files.readString(out.resolve("rc.json"))).contains("\"maxcc\" : 0");
        assertThat(out.resolve("datasets")).doesNotExist();
        assertThat(out.resolve("db2_after")).doesNotExist();
        assertThat(out.resolve("sysout")).doesNotExist();
    }

    @Test
    void implementedStepsWriteCopybookKeyedJsonLinesAndStubFeedsDownstream() throws IOException {
        List<List<TransferPosted>> reconInputs = new ArrayList<>();
        TransferIntake intake = (daily, xrefs, accounts) -> new TransferIntake.IntakeResult(
                List.of(requested()), List.of(),
                new StepReport("STEP010", 0, List.of("CBXFR01C: RECORDS READ 000000001")));
        Reconciliation recon = posted -> {
            reconInputs.add(posted);
            return new Reconciliation.ReconResult(List.of(" TRANSFER FEE RECONCILIATION"),
                    new StepReport("STEP030", 0, List.of()));
        };

        Map<String, Integer> steps = engine(intake, null, recon).run(options);

        Path out = work.resolve("out");
        assertThat(steps).containsExactly(Map.entry("STEP010", 0), Map.entry("STEP030", 0));
        List<Map<String, String>> extract =
                JsonLines.read(out.resolve("datasets").resolve(LegacyRecords.EXTRACT_DSN + ".jsonl"));
        assertThat(extract).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("XFR-TRAN-ID", "TRN0000000000002");
            assertThat(row).containsEntry("XFR-TRAN-AMT", "100.00");
            assertThat(row).containsEntry("XFR-TRAN-DT", "2024-06-20");
        });
        assertThat(reconInputs).singleElement().isEqualTo(List.of(posted()));
        assertThat(out.resolve("sysout").resolve("STEP010.txt"))
                .hasContent("CBXFR01C: RECORDS READ 000000001");
        assertThat(JsonLines.read(out.resolve("datasets").resolve(LegacyRecords.RECON_DSN + ".jsonl")))
                .containsExactly(Map.of("line", " TRANSFER FEE RECONCILIATION"));
    }

    @Test
    void postingAbendKeepsNoDatasetsRestoresLedgerAndSkipsStep030() throws IOException {
        AccountPosting posting = (transfers, accounts, ledger) -> new AccountPosting.PostingResult(
                List.of(), List.of(), ledger, List.of(), new StepReport("STEP020", 8, List.of("ABEND")));
        Reconciliation recon = posted -> {
            throw new AssertionError("STEP030 must not run after STEP020 RC 8");
        };

        Map<String, Integer> steps = engine(null, posting, recon).run(options);

        Path out = work.resolve("out");
        assertThat(steps).containsExactly(Map.entry("STEP020", 8));
        assertThat(Files.readString(out.resolve("rc.json"))).contains("\"maxcc\" : 8");
        assertThat(out.resolve("datasets")).doesNotExist();
        assertThat(out.resolve("db2_after").resolve("XFER_FEE_LEDGER.csv")).hasSameTextualContentAs(
                FIXTURES.resolve("default").resolve("db2_before").resolve("XFER_FEE_LEDGER.csv"));
    }

    @Test
    void nonZeroIntakeRcStopsJobBeforePosting() throws IOException {
        TransferIntake intake = (daily, xrefs, accounts) -> new TransferIntake.IntakeResult(
                List.of(requested()), List.of(), new StepReport("STEP010", 8, List.of("ABEND")));
        AccountPosting posting = (transfers, accounts, ledger) -> {
            throw new AssertionError("STEP020 must not run after STEP010 RC 8");
        };
        Reconciliation recon = posted -> {
            throw new AssertionError("STEP030 must not run after STEP010 RC 8");
        };

        Map<String, Integer> steps = engine(intake, posting, recon).run(options);

        Path out = work.resolve("out");
        assertThat(steps).containsExactly(Map.entry("STEP010", 8));
        assertThat(out.resolve("datasets")).doesNotExist();
        assertThat(out.resolve("db2_after").resolve("XFER_FEE_LEDGER.csv")).hasSameTextualContentAs(
                FIXTURES.resolve("default").resolve("db2_before").resolve("XFER_FEE_LEDGER.csv"));
    }

    @Test
    void rcFourAlsoStopsJobLikeRunjcl() throws IOException {
        TransferIntake intake = (daily, xrefs, accounts) -> new TransferIntake.IntakeResult(
                List.of(), List.of(), new StepReport("STEP010", 4, List.of("NO TRANSFERS")));
        Reconciliation recon = posted -> {
            throw new AssertionError("STEP030 must not run after STEP010 RC 4");
        };

        Map<String, Integer> steps = engine(intake, null, recon).run(options);

        assertThat(steps).containsExactly(Map.entry("STEP010", 4));
        assertThat(work.resolve("out").resolve("datasets")).doesNotExist();
    }

    private static ReplayEngine engine(TransferIntake intake, AccountPosting posting, Reconciliation recon) {
        return new ReplayEngine(Optional.empty(), Optional.ofNullable(intake), Optional.ofNullable(posting),
                Optional.ofNullable(recon));
    }

    private static TransferRequested requested() {
        return new TransferRequested("TRN0000000000002", LocalDate.of(2024, 6, 20), 1L, 2L, "RETAIL",
                new BigDecimal("100.00"), "4000000000000001");
    }

    private static TransferPosted posted() {
        return new TransferPosted("TRN0000000000002", LocalDate.of(2024, 6, 20), 1L, 2L, "RETAIL",
                new BigDecimal("100.00"), new BigDecimal("0.015000"), new BigDecimal("1.50"), false,
                LocalDate.of(2024, 6, 15));
    }
}
