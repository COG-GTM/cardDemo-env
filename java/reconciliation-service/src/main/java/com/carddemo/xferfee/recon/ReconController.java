package com.carddemo.xferfee.recon;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/recon")
public class ReconController {

    private final ReconciliationService service;

    public ReconController(ReconciliationService service) {
        this.service = service;
    }

    @GetMapping("/{businessDate}")
    public ResponseEntity<ReconSummary> summary(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return ResponseEntity.of(service.summary(businessDate));
    }

    /** Legacy {@code XFER.RECON.RPT} text; 204 when STEP030 would be bypassed. */
    @GetMapping(value = "/{businessDate}/legacy-report", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> legacyReport(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(defaultValue = "0") int postingRc) {
        return service.legacyReport(businessDate, postingRc)
                .map(report -> ResponseEntity.ok()
                        .header("X-Return-Code", Integer.toString(report.returnCode()))
                        .body(report.text()))
                .orElseGet(() -> ResponseEntity.noContent()
                        .header("X-Recon-Skipped", "COND=(4,LT,STEP020) posting RC " + postingRc)
                        .build());
    }
}
