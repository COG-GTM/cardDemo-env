package com.carddemo.xfer.recon;

import com.carddemo.xfer.contracts.BatchPosted;
import com.carddemo.xfer.contracts.Topics;
import com.carddemo.xfer.contracts.TransferPosted;
import com.carddemo.xfer.contracts.XferEvent;
import com.carddemo.xfer.contracts.XferJson;
import com.carddemo.xfer.legacy.ReconReport;
import com.carddemo.xfer.support.RunLog;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** CBXFR03C: renders the legacy reconciliation report once posting closes the run. */
@Component
public class Reconciliation {

    static final String STEP = "STEP030";

    private final JdbcTemplate jdbc;
    private final RunLog runLog;
    private final int maxPostingRc;

    public Reconciliation(JdbcTemplate jdbc, RunLog runLog,
                          @Value("${xfer.posting.mode}") String postingMode) {
        this.jdbc = jdbc;
        this.runLog = runLog;
        // batch-atomic reproduces runjcl (any non-zero RC ends the chain); per-transfer follows
        // the JCL COND=(4,LT,STEP020).
        this.maxPostingRc = postingMode.trim().equalsIgnoreCase("per-transfer") ? 4 : 0;
    }

    @KafkaListener(topics = Topics.TRANSFER_POSTED, groupId = "reconciliation")
    @Transactional
    public void onMessage(String payload) {
        XferEvent event = XferJson.read(payload);
        if (runLog.stepRc(event.runId(), STEP).isPresent()) {
            return;
        }
        switch (event) {
            case TransferPosted posted -> jdbc.update(
                    "INSERT INTO xfer_java.recon_entry (run_id, seq, tran_id, tran_dt, book_id, "
                            + "tran_amt, fee_amt) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    posted.runId(), posted.seq(), posted.tranId(), posted.tranDt(), posted.bookId(),
                    posted.tranAmt(), posted.feeAmt());
            case BatchPosted batch when batch.rc() <= maxPostingRc -> report(batch.runId());
            default -> {
            }
        }
    }

    private void report(String runId) {
        List<ReconReport.Entry> entries = jdbc.query(
                "SELECT tran_id, tran_dt, book_id, tran_amt, fee_amt FROM xfer_java.recon_entry "
                        + "WHERE run_id = ? ORDER BY seq",
                (rs, i) -> new ReconReport.Entry(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5)),
                runId);
        ReconReport.Result result = ReconReport.render(entries);
        for (int i = 0; i < result.lines().size(); i++) {
            jdbc.update("INSERT INTO xfer_java.recon_report_line (run_id, line_no, line) "
                    + "VALUES (?, ?, ?)", runId, i + 1, result.lines().get(i));
        }
        for (String line : result.sysout()) {
            runLog.sysout(runId, STEP, line);
        }
        runLog.stepDone(runId, STEP, result.rc());
    }
}
