package com.carddemo.xferfee.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Db2CsvTest {

    @TempDir
    Path work;

    @Test
    void quotedBookIdsWithCommasAndQuotesRoundTrip() throws IOException {
        Path csv = work.resolve("CTL_XFER_PARM.csv");
        Files.writeString(csv, String.join("\n",
                "book_id,fee_pct,fee_cap,eff_dt,exp_dt",
                "\"RETAIL,A  \",0.015000,25.00,2024-06-15,9999-12-31",
                "\"Q\"\"B       \",0.005000,500.00,2020-01-01,9999-12-31",
                ""));

        List<FeeRule> rules = Db2Csv.readFeeRules(csv);

        assertThat(rules).extracting(FeeRule::bookId).containsExactly("RETAIL,A", "Q\"B");
        assertThat(rules.get(0).feePct()).isEqualByComparingTo("0.015");
        Path out = work.resolve("out.csv");
        Db2Csv.writeFeeRules(out, rules);
        assertThat(out).hasSameTextualContentAs(csv);
    }

    @Test
    void fixtureDumpsRoundTripByteForByte() throws IOException {
        Path before = Path.of("..", "..", "fixtures", "xferfee", "default", "expected", "db2_after");
        Path parm = work.resolve("parm.csv");
        Path ledger = work.resolve("ledger.csv");
        Db2Csv.writeFeeRules(parm, Db2Csv.readFeeRules(before.resolve("CTL_XFER_PARM.csv")));
        List<LedgerEntry> entries = Db2Csv.readLedger(before.resolve("XFER_FEE_LEDGER.csv"));
        Db2Csv.writeLedger(ledger, entries);
        assertThat(entries).isNotEmpty();
        assertThat(entries.get(0).amount()).isEqualTo(new BigDecimal("100.00"));
        assertThat(entries.get(0).tranDate()).isEqualTo(LocalDate.of(2024, 6, 20));
        assertThat(parm).hasSameTextualContentAs(before.resolve("CTL_XFER_PARM.csv"));
        assertThat(ledger).hasSameTextualContentAs(before.resolve("XFER_FEE_LEDGER.csv"));
    }
}
