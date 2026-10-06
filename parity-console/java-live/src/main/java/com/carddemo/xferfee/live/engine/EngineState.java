package com.carddemo.xferfee.live.engine;

import java.util.List;

public record EngineState(String roundingMode, List<FeeRule> rules, List<Account> accounts,
        List<LedgerRow> ledger) {
}
