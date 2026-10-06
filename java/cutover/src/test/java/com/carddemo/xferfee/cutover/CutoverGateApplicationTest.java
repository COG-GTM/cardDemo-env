package com.carddemo.xferfee.cutover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CutoverGateApplicationTest {

    @TempDir
    Path root;

    private void shadowDay(LocalDate date, String status, boolean withRateChange) throws IOException {
        Path dir = root.resolve("shadow").resolve(date.toString());
        Files.createDirectories(dir.resolve("rules"));
        Files.writeString(dir.resolve("report.json"),
                "{\"date\": \"" + date + "\", \"status\": \"" + status + "\", \"exit_code\": 0}\n");
        String rules = "book_id,fee_pct,fee_cap,eff_dt,exp_dt\n"
                + "INSTL     ,0.005000,500.00,2020-01-01,9999-12-31\n"
                + "RETAIL    ,0.012500,25.00,2020-01-01,2024-06-15\n"
                + (withRateChange ? "RETAIL    ,0.015000,25.00,2024-06-15,9999-12-31\n" : "");
        Files.writeString(dir.resolve("rules").resolve("CTL_XFER_PARM.csv"), rules);
    }

    private void parity(String javaVerdict) throws IOException {
        Path parity = root.resolve("parity");
        StringBuilder cobol = new StringBuilder();
        for (String name : CutoverGateApplication.ALL_CASES) {
            cobol.append("# Parity: xferfee / ").append(name).append(" \u2014 PASS\n\nPARITY: PASS\n\n");
            Files.createDirectories(parity.resolve(name));
            Files.writeString(parity.resolve(name).resolve("java-report.md"),
                    "# Parity: xferfee / " + name + " \u2014 " + javaVerdict + "\n\nPARITY: " + javaVerdict + "\n");
        }
        Files.writeString(parity.resolve("report.md"), cobol.toString());
    }

    private void rollback(String status, boolean ok) throws IOException {
        rollbackJson("{\"status\": \"" + status + "\", \"finished_at\": \"" + java.time.Instant.now() + "\", "
                + "\"source_kind\": \"java\", \"commit\": \"abc1234def\", "
                + "\"checks\": [{\"name\": \"ledger_vs_fees\", \"ok\": " + ok + "}]}\n");
    }

    private void rollbackJson(String json) throws IOException {
        Files.writeString(root.resolve("rollback.json"), json);
    }

    private void allGreenExceptRollback() throws IOException {
        shadowDay(LocalDate.of(2024, 6, 14), "PASS", true);
        shadowDay(LocalDate.of(2024, 6, 15), "PASS", true);
        shadowDay(LocalDate.of(2024, 6, 16), "PASS", true);
        parity("PASS");
    }

    private String readiness() throws IOException {
        return Files.readString(root.resolve("out").resolve("readiness.md"));
    }

    private int gate(String... extra) throws IOException {
        List<String> args = new java.util.ArrayList<>(List.of(
                "--shadow-root", root.resolve("shadow").toString(),
                "--parity-root", root.resolve("parity").toString(),
                "--rollback", root.resolve("rollback.json").toString(),
                "--out", root.resolve("out").toString(),
                "--required-days", "3",
                "--as-of", "2024-06-16"));
        args.addAll(List.of(extra));
        return CutoverGateApplication.run(args.toArray(String[]::new));
    }

    @Test
    void goWhenAllGatesMet() throws IOException {
        shadowDay(LocalDate.of(2024, 6, 14), "PASS", false);
        shadowDay(LocalDate.of(2024, 6, 15), "PASS", true);
        shadowDay(LocalDate.of(2024, 6, 16), "PASS", true);
        parity("PASS");
        rollback("PASS", true);
        assertEquals(0, gate());
        String md = Files.readString(root.resolve("out").resolve("readiness.md"));
        assertTrue(md.startsWith("# xferfee cut-over readiness: GO"), md);
        assertTrue(Files.readString(root.resolve("out").resolve("readiness.json")).contains("\"verdict\" : \"GO\""));
    }

    @Test
    void noGoWhenJavaReplayFailsOrRollbackMissing() throws IOException {
        shadowDay(LocalDate.of(2024, 6, 14), "PASS", true);
        shadowDay(LocalDate.of(2024, 6, 15), "PASS", true);
        shadowDay(LocalDate.of(2024, 6, 16), "PASS", true);
        parity("FAIL");
        assertEquals(1, gate());
        String md = Files.readString(root.resolve("out").resolve("readiness.md"));
        assertTrue(md.contains("G2 replay: half_cent cobol=PASS java=FAIL"), md);
        assertTrue(md.contains("G3 rollback: rehearsal NOT_RUN"), md);
    }

    @Test
    void noGoWhenRollbackCheckFailed() throws IOException {
        shadowDay(LocalDate.of(2024, 6, 15), "PASS", true);
        parity("PASS");
        rollback("PASS", false);
        assertEquals(1, gate("--required-days", "1"));
        assertTrue(Files.readString(root.resolve("out").resolve("readiness.md"))
                .contains("failed [ledger_vs_fees]"));
    }

    @Test
    void rateChangeDatesOnlyCountSupersedingRules() throws IOException {
        shadowDay(LocalDate.of(2024, 6, 15), "PASS", true);
        Path rules = root.resolve("shadow/2024-06-15/rules/CTL_XFER_PARM.csv");
        assertEquals(Set.of(LocalDate.of(2024, 6, 15)), ShadowRunHistory.rateChangeDates(rules));
    }

    @Test
    void parsesCompareReportHeadings() {
        Map<String, Boolean> verdicts = ParityReports.parse(
                "# Parity: xferfee / default \u2014 PASS\n\nPARITY: PASS\n\n"
                        + "# Parity: xferfee / half_cent \u2014 FAIL\n\nPARITY: FAIL (1 field differences)\n");
        assertEquals(Map.of("default", true, "half_cent", false), verdicts);
    }

    @Test
    void readsFoundationParityJavaReportLocation() throws IOException {
        Path parity = root.resolve("parity");
        Files.createDirectories(parity);
        Files.writeString(parity.resolve("report.md"), "# Parity: xferfee / default \u2014 PASS\n");
        Path foundation = root.resolve("parity-java").resolve("default");
        Files.createDirectories(foundation);
        Files.writeString(foundation.resolve("report.md"), "# Parity: xferfee / default \u2014 PASS\n");
        ParityReports.FinalReplay replay = ParityReports.finalReplay(parity, List.of("default"));
        assertTrue(replay.met());
    }

    @Test
    void rejectsUnknownArguments() throws IOException {
        assertEquals(2, gate("--bogus"));
    }

    @Test
    void fixtureStandInRehearsalDoesNotMeetG3() throws IOException {
        allGreenExceptRollback();
        rollbackJson("{\"status\": \"PASS\", \"finished_at\": \"" + java.time.Instant.now() + "\", "
                + "\"source_kind\": \"fixture\", \"checks\": [{\"name\": \"a\", \"ok\": true}]}");
        assertEquals(1, gate());
        assertTrue(readiness().contains("G3 rollback: rehearsal generation came from fixture"), readiness());
        assertEquals(0, gate("--allow-fixture-rehearsal"));
    }

    @Test
    void rehearsalWithoutChecksDoesNotMeetG3() throws IOException {
        allGreenExceptRollback();
        rollbackJson("{\"status\": \"PASS\", \"finished_at\": \"" + java.time.Instant.now() + "\", "
                + "\"source_kind\": \"java\"}");
        assertEquals(1, gate());
        assertTrue(readiness().contains("G3 rollback: rehearsal has no checks"), readiness());
    }

    @Test
    void staleRehearsalDoesNotMeetG3() throws IOException {
        allGreenExceptRollback();
        rollbackJson("{\"status\": \"PASS\", \"finished_at\": \"2024-01-02T00:00:00+00:00\", "
                + "\"source_kind\": \"java\", \"checks\": [{\"name\": \"a\", \"ok\": true}]}");
        assertEquals(1, gate());
        assertTrue(readiness().contains("older than 7 days"), readiness());
    }

    @Test
    void rehearsalMustMatchReleaseCommit() throws IOException {
        allGreenExceptRollback();
        rollback("PASS", true);
        assertEquals(0, gate("--release-commit", "abc1234"));
        assertEquals(1, gate("--release-commit", "fff0000"));
        assertTrue(readiness().contains("release is fff0000"), readiness());
    }

    @Test
    void staleShadowHistoryIsNoGo() throws IOException {
        allGreenExceptRollback();
        rollback("PASS", true);
        assertEquals(1, gate("--as-of", "2024-07-30"));
        assertTrue(readiness().contains("missing shadow run for 2024-07-30"), readiness());
    }
}
