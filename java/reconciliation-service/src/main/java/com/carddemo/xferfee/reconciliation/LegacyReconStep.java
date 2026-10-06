package com.carddemo.xferfee.reconciliation;

import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.port.Reconciliation;
import com.carddemo.xferfee.contracts.port.ReconciliationReport;
import com.carddemo.xferfee.contracts.port.StepReport;
import com.carddemo.xferfee.reconciliation.legacy.LegacyReconRenderer;
import com.carddemo.xferfee.reconciliation.legacy.LegacyReport;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * STEP030 / CBXFR03C as a {@link Reconciliation} stage. The XFERFEEP condition
 * {@code COND=(4,LT,STEP020)} is applied by the caller ({@code ChainReplay}) or by
 * {@link #run(List, int)}.
 */
@Component
public class LegacyReconStep implements Reconciliation {

    public static final String STEP = "STEP030";
    private static final LegacyReconRenderer RENDERER = new LegacyReconRenderer();

    @Override
    public ReconciliationReport reconcile(List<TransferPosted> posted) {
        return toReport(render(posted));
    }

    /** Empty when STEP030 is bypassed because the posting step ended above RC 4. */
    public Optional<ReconciliationReport> run(List<TransferPosted> posted, int postingRc) {
        return postingRc > 4 ? Optional.empty() : Optional.of(reconcile(posted));
    }

    static LegacyReport render(List<TransferPosted> posted) {
        return RENDERER.render(posted.stream().map(FeeLine::of).toList());
    }

    /** {@code lines} are the line-sequential form GnuCOBOL writes: one record per line, trailing spaces removed. */
    static ReconciliationReport toReport(LegacyReport report) {
        List<String> lines = report.records().stream().map(String::stripTrailing).toList();
        return new ReconciliationReport(lines, new StepReport(STEP, report.returnCode(), report.sysout()));
    }
}
