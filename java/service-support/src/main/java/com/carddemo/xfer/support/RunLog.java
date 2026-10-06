package com.carddemo.xfer.support;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Step return codes and SYSOUT lines, the job-log equivalent of the JCL chain. */
@Component
public class RunLog {

    private final JdbcTemplate jdbc;

    public RunLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void sysout(String runId, String step, String line) {
        jdbc.update("INSERT INTO xfer_java.run_sysout (run_id, step, line) VALUES (?, ?, ?)",
                runId, step, line);
    }

    public void stepDone(String runId, String step, int rc) {
        jdbc.update("INSERT INTO xfer_java.run_step (run_id, step, rc) VALUES (?, ?, ?)",
                runId, step, rc);
    }

    public Optional<Integer> stepRc(String runId, String step) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT rc FROM xfer_java.run_step WHERE run_id = ? AND step = ?",
                Integer.class, runId, step);
        return rows.stream().findFirst();
    }
}
