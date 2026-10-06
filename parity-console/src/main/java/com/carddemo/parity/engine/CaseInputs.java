package com.carddemo.parity.engine;

import com.carddemo.parity.records.Account;
import com.carddemo.parity.records.CardXref;
import com.carddemo.parity.records.DailyTransaction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Loads a {@code fixtures/xferfee/<case>/} input set: master files, feed and CTL_XFER_PARM snapshot. */
public record CaseInputs(
        String name,
        List<Account> accounts,
        List<CardXref> xrefs,
        List<DailyTransaction> feed,
        FeeRuleTable rules) {

    public static CaseInputs load(Path fixturesRoot, String name) {
        Path dir = fixturesRoot.resolve(name);
        Path input = dir.resolve("input");
        try {
            return new CaseInputs(
                    name,
                    XferFeeEngine.split(Files.readAllBytes(input.resolve("ACCTDATA.PS")), Account.LENGTH)
                            .stream().map(Account::new).toList(),
                    XferFeeEngine.split(Files.readAllBytes(input.resolve("CARDXREF.PS")), CardXref.LENGTH)
                            .stream().map(CardXref::parse).toList(),
                    XferFeeEngine.split(Files.readAllBytes(input.resolve("DALYTRAN.PS")), DailyTransaction.LENGTH)
                            .stream().map(DailyTransaction::new).toList(),
                    FeeRuleTable.load(dir.resolve("db2_before").resolve("CTL_XFER_PARM.csv")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public XferFeeEngine newEngine(FeeRounding rounding) {
        return new XferFeeEngine(xrefs, accounts, rules, rounding);
    }
}
