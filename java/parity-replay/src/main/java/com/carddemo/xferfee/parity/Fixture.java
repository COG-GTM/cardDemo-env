package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One fixture case loaded for replay: the three input datasets (decoded to JSON-lines by
 * {@code tools/parity/java_candidate.py inputs}), the {@code db2_before} tables, and the recorded
 * upstream outputs used only to stub steps that are not implemented yet.
 */
record Fixture(
        List<DailyTransaction> dailyTransactions,
        List<CardXref> cardXrefs,
        List<Account> accounts,
        List<FeeRule> feeRules,
        List<LedgerEntry> ledgerBefore,
        List<TransferRequested> recordedExtract,
        List<TransferPosted> recordedFees) {

    static Fixture load(ReplayOptions options) throws IOException {
        Path input = options.input();
        Path before = options.caseDir().resolve("db2_before");
        Path stub = input.resolve("stub");
        return new Fixture(
                rows(input.resolve("DALYTRAN.jsonl"), LegacyRecords::dailyTransaction, true),
                rows(input.resolve("CARDXREF.jsonl"), LegacyRecords::cardXref, true),
                rows(input.resolve("ACCTDATA.jsonl"), LegacyRecords::account, true),
                Db2Csv.readFeeRules(before.resolve(Db2Csv.CTL_XFER_PARM + ".csv")),
                Db2Csv.readLedger(before.resolve(Db2Csv.XFER_FEE_LEDGER + ".csv")),
                rows(stub.resolve(LegacyRecords.EXTRACT_DSN + ".jsonl"), LegacyRecords::transferRequested, false),
                rows(stub.resolve(LegacyRecords.FEES_DSN + ".jsonl"), LegacyRecords::transferPosted, false));
    }

    private static <T> List<T> rows(Path path, Function<Map<String, String>, T> mapper, boolean required)
            throws IOException {
        if (!Files.exists(path)) {
            if (required) {
                throw new IOException("missing replay input " + path
                        + " (run tools/parity/java_candidate.py inputs first)");
            }
            return List.of();
        }
        return JsonLines.read(path).stream().map(mapper).toList();
    }
}
