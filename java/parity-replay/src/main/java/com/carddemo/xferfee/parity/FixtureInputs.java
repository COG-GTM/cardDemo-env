package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A fixture's decoded {@code input/} datasets plus its {@code db2_before/} tables. */
public record FixtureInputs(
        String runDate,
        List<DailyTransaction> dailyTransactions,
        List<CardXref> cardXrefs,
        List<Account> accounts,
        List<FeeRule> feeRules,
        List<LedgerEntry> ledgerBefore) {

    public static final String DALYTRAN = "AWS.M2.CARDDEMO.DALYTRAN.PS";
    public static final String CARDXREF = "AWS.M2.CARDDEMO.CARDXREF.PS";
    public static final String ACCTDATA = "AWS.M2.CARDDEMO.ACCTDATA.PS";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public static FixtureInputs load(ReplayOptions options) {
        try {
            Map<?, ?> caseJson = MAPPER.readValue(options.caseDir().resolve("case.json").toFile(), Map.class);
            Path db2 = options.caseDir().resolve("db2_before");
            return new FixtureInputs(
                    String.valueOf(caseJson.get("run_date")),
                    jsonLines(options.input().resolve(DALYTRAN + ".jsonl"), DailyTransaction.class),
                    jsonLines(options.input().resolve(CARDXREF + ".jsonl"), CardXref.class),
                    jsonLines(options.input().resolve(ACCTDATA + ".jsonl"), Account.class),
                    csv(db2.resolve("CTL_XFER_PARM.csv"), row -> new FeeRule(
                            row.get("book_id").trim(),
                            new BigDecimal(row.get("fee_pct").trim()),
                            new BigDecimal(row.get("fee_cap").trim()),
                            LocalDate.parse(row.get("eff_dt").trim()),
                            LocalDate.parse(row.get("exp_dt").trim()))),
                    csv(db2.resolve("XFER_FEE_LEDGER.csv"), row -> new LedgerEntry(
                            row.get("tran_id").trim(),
                            LocalDate.parse(row.get("tran_dt").trim()),
                            Long.parseLong(row.get("src_acct_id").trim()),
                            Long.parseLong(row.get("tgt_acct_id").trim()),
                            row.get("book_id").trim(),
                            new BigDecimal(row.get("tran_amt").trim()),
                            new BigDecimal(row.get("fee_amt").trim()),
                            "Y".equals(row.get("cap_applied").trim()))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static <T> List<T> jsonLines(Path path, Class<T> type) throws IOException {
        List<T> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (!line.isBlank()) {
                rows.add(MAPPER.readValue(line, type));
            }
        }
        return rows;
    }

    static <T> List<T> csv(Path path, Function<Map<String, String>, T> mapper) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty()) {
            return List.of();
        }
        List<String> header = Arrays.stream(lines.get(0).split(",")).map(String::trim)
                .map(String::toLowerCase).toList();
        List<T> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] cells = line.split(",", -1);
            Map<String, String> row = new HashMap<>();
            for (int index = 0; index < header.size(); index++) {
                row.put(header.get(index), index < cells.length ? cells[index] : "");
            }
            rows.add(mapper.apply(row));
        }
        return rows;
    }
}
