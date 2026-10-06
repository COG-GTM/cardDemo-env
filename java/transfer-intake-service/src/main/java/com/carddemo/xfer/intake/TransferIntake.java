package com.carddemo.xfer.intake;

import com.carddemo.xfer.contracts.DailyTranReceived;
import com.carddemo.xfer.contracts.EndOfDay;
import com.carddemo.xfer.contracts.ExtractClosed;
import com.carddemo.xfer.contracts.Topics;
import com.carddemo.xfer.contracts.TransferRequested;
import com.carddemo.xfer.contracts.XferEvent;
import com.carddemo.xfer.contracts.XferJson;
import com.carddemo.xfer.support.Outbox;
import com.carddemo.xfer.support.RunLog;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** CBXFR01C: select type-08 transactions and resolve card → account → book. */
@Component
public class TransferIntake {

    static final String STEP = "STEP010";
    static final String TRANSFER_TYPE = "08";
    static final int TABLE_LIMIT = 500;

    private final JdbcTemplate jdbc;
    private final RunLog runLog;
    private final Outbox outbox;

    public TransferIntake(JdbcTemplate jdbc, RunLog runLog, Outbox outbox) {
        this.jdbc = jdbc;
        this.runLog = runLog;
        this.outbox = outbox;
    }

    @KafkaListener(topics = Topics.DAILY_TRAN, groupId = "transfer-intake")
    @Transactional
    public void onMessage(String payload) {
        XferEvent event = XferJson.read(payload);
        if (runLog.stepRc(event.runId(), STEP).isPresent()) {
            return;
        }
        jdbc.update("INSERT INTO xfer_java.intake_run (run_id) VALUES (?) ON CONFLICT DO NOTHING",
                event.runId());
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT read_count, selected_count, unmatched_count, last_seq "
                        + "FROM xfer_java.intake_run WHERE run_id = ? FOR UPDATE", event.runId());
        switch (event) {
            case DailyTranReceived tran -> receive(tran, ((Number) run.get("last_seq")).longValue());
            case EndOfDay eod -> close(eod, run);
            default -> {
            }
        }
    }

    private void receive(DailyTranReceived tran, long lastSeq) {
        if (tran.seq() <= lastSeq) {
            return;
        }
        boolean selected = false;
        boolean unmatched = false;
        if (TRANSFER_TYPE.equals(tran.tranTypeCd())) {
            List<Long> accts = jdbc.queryForList(
                    "SELECT acct_id FROM xfer_java.card_xref WHERE seq <= ? AND card_num = ? "
                            + "ORDER BY seq LIMIT 1", Long.class, TABLE_LIMIT, tran.tranCardNum());
            if (accts.isEmpty()) {
                runLog.sysout(tran.runId(), STEP, "CBXFR01C: CARD NOT FOUND " + tran.tranCardNum());
                unmatched = true;
            } else {
                long srcAcct = accts.get(0);
                List<String> books = jdbc.queryForList(
                        "SELECT group_id FROM xfer_java.account WHERE seq <= ? AND acct_id = ? "
                                + "ORDER BY seq LIMIT 1", String.class, TABLE_LIMIT, srcAcct);
                if (books.isEmpty()) {
                    runLog.sysout(tran.runId(), STEP,
                            "CBXFR01C: ACCOUNT NOT FOUND " + String.format("%011d", srcAcct));
                    unmatched = true;
                } else {
                    extract(tran, srcAcct, books.get(0));
                    selected = true;
                }
            }
        }
        jdbc.update("UPDATE xfer_java.intake_run SET read_count = read_count + 1, "
                        + "selected_count = selected_count + ?, unmatched_count = unmatched_count + ?, "
                        + "last_seq = ? WHERE run_id = ?",
                selected ? 1 : 0, unmatched ? 1 : 0, tran.seq(), tran.runId());
    }

    private void extract(DailyTranReceived tran, long srcAcct, String bookId) {
        TransferRequested request = new TransferRequested(
                tran.runId(),
                tran.seq(),
                tran.tranId(),
                tran.tranOrigTs().substring(0, 10),
                srcAcct,
                targetAccount(tran.tranDesc()),
                bookId,
                tran.tranAmt(),
                tran.tranCardNum());
        jdbc.update("INSERT INTO xfer_java.extract_record (run_id, seq, tran_id, tran_dt, "
                        + "src_acct_id, tgt_acct_id, book_id, tran_amt, card_num) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                request.runId(), request.seq(), request.tranId(), request.tranDt(),
                request.srcAcctId(), request.tgtAcctId(), request.bookId(), request.tranAmt(),
                request.cardNum());
        outbox.append(Topics.TRANSFER_REQUESTED, request);
    }

    /** {@code MOVE TRAN-DESC(14:11) TO XFR-TGT-ACCT-ID}. */
    static long targetAccount(String desc) {
        String padded = String.format("%-100s", desc);
        StringBuilder digits = new StringBuilder();
        for (char c : padded.substring(13, 24).toCharArray()) {
            digits.append(Character.isDigit(c) ? c : '0');
        }
        return Long.parseLong(digits.toString());
    }

    private void close(EndOfDay eod, Map<String, Object> run) {
        long read = ((Number) run.get("read_count")).longValue();
        long selected = ((Number) run.get("selected_count")).longValue();
        long unmatched = ((Number) run.get("unmatched_count")).longValue();
        runLog.sysout(eod.runId(), STEP, "CBXFR01C: RECORDS READ " + String.format("%09d", read));
        runLog.sysout(eod.runId(), STEP, "CBXFR01C: TRANSFERS SELECTED " + String.format("%09d", selected));
        runLog.sysout(eod.runId(), STEP, "CBXFR01C: UNMATCHED CARDS " + String.format("%09d", unmatched));
        int rc = unmatched > 0 ? 4 : 0;
        runLog.stepDone(eod.runId(), STEP, rc);
        outbox.append(Topics.TRANSFER_REQUESTED, new ExtractClosed(eod.runId(), selected, rc));
    }
}
