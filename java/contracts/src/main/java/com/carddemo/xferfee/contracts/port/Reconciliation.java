package com.carddemo.xferfee.contracts.port;

import com.carddemo.xferfee.contracts.TransferPosted;
import java.util.List;

/** STEP030 / CBXFR03C: per-book subtotals and grand totals of posted fees. */
public interface Reconciliation {

    ReconciliationReport reconcile(List<TransferPosted> posted);
}
