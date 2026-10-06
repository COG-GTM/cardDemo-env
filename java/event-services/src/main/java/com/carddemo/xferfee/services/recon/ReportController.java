package com.carddemo.xferfee.services.recon;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The XFER.RECON.RPT generation of a run, as printed. */
@RestController
public class ReportController {

    private final JdbcTemplate jdbc;

    public ReportController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping(value = "/api/runs/{runId}/report", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> report(@PathVariable String runId) {
        List<String> lines = jdbc.queryForList(
                "SELECT text FROM recon.report_line WHERE run_id = ? ORDER BY line_no", String.class, runId);
        return lines.isEmpty() ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(String.join("\n", lines) + "\n");
    }
}
