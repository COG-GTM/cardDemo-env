package com.carddemo.parity.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory CTL_XFER_PARM loaded from a {@code db2_before/CTL_XFER_PARM.csv} snapshot. */
public final class FeeRuleTable {

    private final List<FeeRule> rules;
    private final List<String> csvLines;

    private FeeRuleTable(List<FeeRule> rules, List<String> csvLines) {
        this.rules = List.copyOf(rules);
        this.csvLines = List.copyOf(csvLines);
    }

    public static FeeRuleTable load(Path csv) {
        try {
            List<String> lines = Files.readAllLines(csv);
            String[] header = lines.get(0).toLowerCase().split(",");
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                index.put(header[i].strip(), i);
            }
            List<FeeRule> rules = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split(",", -1);
                rules.add(new FeeRule(
                        cols[index.get("book_id")],
                        new BigDecimal(cols[index.get("fee_pct")].strip()),
                        new BigDecimal(cols[index.get("fee_cap")].strip()),
                        LocalDate.parse(cols[index.get("eff_dt")].strip()),
                        LocalDate.parse(cols[index.get("exp_dt")].strip())));
            }
            return new FeeRuleTable(rules, lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<FeeRule> lookup(String book, LocalDate date) {
        return rules.stream().filter(rule -> rule.appliesTo(book, date)).findFirst();
    }

    public List<String> csvLines() {
        return csvLines;
    }
}
