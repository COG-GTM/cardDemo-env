package com.carddemo.xferfee.shadow;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one XFRDAILY run leaves behind. A {@code null} list means the step that allocates that dataset never
 * ran (the JCL harness stops at the first non-zero step RC).
 */
public record ChainResult(
        List<TransferRequested> extract,
        List<TransferRejected> rejected,
        List<Account> accountsOut,
        List<TransferPosted> posted,
        List<String> reconReport,
        List<FeeRule> rules,
        List<LedgerEntry> ledger,
        Map<String, List<String>> sysout,
        LinkedHashMap<String, Integer> stepRc) {

    public int maxcc() {
        return stepRc.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }
}
