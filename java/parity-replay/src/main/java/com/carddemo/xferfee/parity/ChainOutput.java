package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one replayed run produced, already filtered by JCL disposition rules: a dataset is
 * {@code null} when its step did not run or ended abnormally ({@code DISP=(NEW,CATLG,DELETE)}).
 */
record ChainOutput(
        List<StepReport> steps,
        List<TransferRequested> extract,
        List<Account> accountMasterAfter,
        List<TransferPosted> fees,
        List<String> reconReport,
        List<FeeRule> feeRulesAfter,
        List<LedgerEntry> ledgerAfter) {

    static final String EXTRACT = "AWS.M2.CARDDEMO.XFER.EXTRACT";
    static final String ACCTDATA = "AWS.M2.CARDDEMO.ACCTDATA.XFER";
    static final String FEES = "AWS.M2.CARDDEMO.XFER.FEES";
    static final String RECON = "AWS.M2.CARDDEMO.XFER.RECON.RPT";

    /** Highest RC that still ends a step normally (output generations are catalogued). */
    static final int NORMAL_END_MAX_RC = 4;

    static boolean kept(StepReport report) {
        return report != null && report.returnCode() <= NORMAL_END_MAX_RC;
    }

    /** {@code STEP030 ... COND=(4,LT,STEP020)}: bypass when 4 is less than STEP020's RC. */
    static boolean reconBypassed(StepReport step020) {
        return step020 == null || 4 < step020.returnCode();
    }

    void write(Path out) {
        Path datasets = out.resolve("datasets");
        if (extract != null) {
            JsonLines.write(datasets.resolve(EXTRACT + ".jsonl"), extract);
        }
        if (accountMasterAfter != null) {
            JsonLines.write(datasets.resolve(ACCTDATA + ".jsonl"), accountMasterAfter);
        }
        if (fees != null) {
            JsonLines.write(datasets.resolve(FEES + ".jsonl"), fees);
        }
        if (reconReport != null) {
            JsonLines.writeLines(datasets.resolve(RECON + ".txt"), reconReport);
        }
        JsonLines.write(out.resolve("db2_after/CTL_XFER_PARM.jsonl"), feeRulesAfter);
        JsonLines.write(out.resolve("db2_after/XFER_FEE_LEDGER.jsonl"), ledgerAfter);
        Map<String, Integer> codes = new LinkedHashMap<>();
        int maxcc = 0;
        for (StepReport step : steps) {
            JsonLines.writeLines(out.resolve("sysout/" + step.step() + ".txt"), step.sysout());
            codes.put(step.step(), step.returnCode());
            maxcc = Math.max(maxcc, step.returnCode());
        }
        JsonLines.writeText(out.resolve("rc.json"),
                com.carddemo.xferfee.events.EventJson.write(Map.of("steps", codes, "maxcc", maxcc)) + "\n");
    }
}
