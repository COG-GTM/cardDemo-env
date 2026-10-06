package com.carddemo.replay;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Inputs of one fixtures/xferfee/&lt;case&gt; directory. */
record FixtureCase(String name, List<byte[]> dailyTransactions, List<byte[]> cardXref,
                   List<byte[]> accounts, List<FeeRule> feeRules) {

    static final int DALYTRAN_LRECL = 350;
    static final int CARDXREF_LRECL = 50;
    static final int ACCTDATA_LRECL = 300;

    static FixtureCase load(Path dir) throws IOException {
        Path input = dir.resolve("input");
        return new FixtureCase(
                dir.getFileName().toString(),
                FixedRecords.read(input.resolve("DALYTRAN.PS"), DALYTRAN_LRECL),
                FixedRecords.read(input.resolve("CARDXREF.PS"), CARDXREF_LRECL),
                FixedRecords.read(input.resolve("ACCTDATA.PS"), ACCTDATA_LRECL),
                loadRules(dir.resolve("db2_before").resolve("CTL_XFER_PARM.csv")));
    }

    private static List<FeeRule> loadRules(Path csv) throws IOException {
        List<FeeRule> rules = new ArrayList<>();
        List<String> lines = Files.readAllLines(csv);
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] cols = line.split(",", -1);
            rules.add(new FeeRule(cols[0], new BigDecimal(cols[1].strip()), new BigDecimal(cols[2].strip()),
                    cols[3].strip(), cols[4].strip()));
        }
        return rules;
    }
}
