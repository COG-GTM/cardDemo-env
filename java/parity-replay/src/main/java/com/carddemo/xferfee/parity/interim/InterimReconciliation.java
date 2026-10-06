package com.carddemo.xferfee.parity.interim;

import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.observability.SysoutFormat;
import java.math.BigDecimal;
import java.util.List;

/** CBXFR03C SYSOUT and RC only (BR-18/BR-19); the report text is COG-1239's. */
public class InterimReconciliation implements Reconciliation {

    @Override
    public ReconResult reconcile(List<TransferPosted> posted) {
        if (posted.isEmpty()) {
            return new ReconResult(List.of(), new StepReport("STEP030", 4, List.of("CBXFR03C: NO FEE RECORDS")));
        }
        BigDecimal grand = BigDecimal.ZERO.setScale(2);
        for (TransferPosted transfer : posted) {
            grand = grand.add(transfer.feeAmount());
        }
        return new ReconResult(List.of(),
                new StepReport("STEP030", 0, List.of("CBXFR03C: GRAND TOTAL FEE " + SysoutFormat.signedMoney(grand))));
    }
}
