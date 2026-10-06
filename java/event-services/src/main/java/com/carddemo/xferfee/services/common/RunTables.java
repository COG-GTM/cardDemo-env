package com.carddemo.xferfee.services.common;

import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.events.EventJson;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Per-run tables every service has: {@code (run_id, seq, payload)} logs and {@code step_report}. */
public final class RunTables {

    private final JdbcTemplate jdbc;
    private final String schema;

    public RunTables(JdbcTemplate jdbc, String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    public void append(String table, String runId, long seq, Object payload) {
        jdbc.update("INSERT INTO " + schema + "." + table + " (run_id, seq, payload) VALUES (?, ?, ?)",
                runId, seq, EventJson.write(payload));
    }

    public <T> List<T> read(String table, String runId, Class<T> type) {
        return jdbc.query("SELECT payload FROM " + schema + "." + table + " WHERE run_id = ? ORDER BY seq",
                (rs, n) -> EventJson.read(rs.getString(1), type), runId);
    }

    public long count(String table, String runId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + schema + "." + table + " WHERE run_id = ?",
                Long.class, runId);
        return count == null ? 0 : count;
    }

    public void saveStep(String runId, StepReport report) {
        jdbc.update("INSERT INTO " + schema + ".step_report (run_id, step, return_code, sysout) VALUES (?, ?, ?, ?)",
                runId, report.step(), report.returnCode(), EventJson.write(report.sysout()));
    }
}
