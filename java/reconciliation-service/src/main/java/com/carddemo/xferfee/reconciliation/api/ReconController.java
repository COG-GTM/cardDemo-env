package com.carddemo.xferfee.reconciliation.api;

import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.reconciliation.BookTotal;
import com.carddemo.xferfee.reconciliation.DailyReconciliation;
import com.carddemo.xferfee.reconciliation.ReconciliationException;
import com.carddemo.xferfee.reconciliation.ReconciliationException.Kind;
import com.carddemo.xferfee.reconciliation.ReconciliationService;
import com.carddemo.xferfee.reconciliation.legacy.LegacyReport;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/recon/{businessDate}")
public class ReconController {

    private static final MediaType TEXT = MediaType.parseMediaType("text/plain;charset=US-ASCII");
    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=US-ASCII");

    private final ReconciliationService service;

    public ReconController(ReconciliationService service) {
        this.service = service;
    }

    /** True per-book totals for the business date. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public DailyReconciliation get(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return service.find(businessDate).orElseThrow(() -> unknown(businessDate));
    }

    @GetMapping(value = "/books.csv")
    public ResponseEntity<String> booksCsv(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        DailyReconciliation day = get(businessDate);
        StringBuilder csv = new StringBuilder("book_id,count,amount,fee\n");
        for (BookTotal book : day.books()) {
            csv.append(csvCell(book.bookId())).append(',').append(book.count()).append(',')
                    .append(book.amount().toPlainString()).append(',').append(book.fee().toPlainString()).append('\n');
        }
        return ResponseEntity.ok().contentType(CSV).body(csv.toString());
    }

    /**
     * The legacy XFER.RECON.RPT. {@code format=line} (default) is the recorded GnuCOBOL
     * line-sequential form; {@code format=fba} returns fixed 133-byte records.
     */
    @GetMapping("/report")
    public ResponseEntity<byte[]> legacyReport(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(defaultValue = "line") String format) {
        LegacyReport report = service.legacyReport(businessDate)
                .orElseThrow(() -> new ReconciliationException(Kind.NO_REPORT,
                        "report skipped for " + businessDate + " (posting RC > 4)"));
        byte[] body = "fba".equalsIgnoreCase(format) ? report.fixedBlockBytes() : report.lineSequentialBytes();
        return ResponseEntity.ok().contentType(TEXT)
                .header("X-Legacy-Return-Code", Integer.toString(report.returnCode()))
                .body(body);
    }

    @PostMapping("/posted")
    public ResponseEntity<Void> posted(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
                                       @RequestBody TransferPosted event) {
        service.onPosted(businessDate, event);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/rejected")
    public ResponseEntity<Void> rejected(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
                                         @RequestBody TransferRejected event) {
        service.onRejected(businessDate, event);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/close")
    public DailyReconciliation close(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
                                     @RequestParam(defaultValue = "0") int postingRc) {
        return service.close(businessDate, postingRc);
    }

    @ExceptionHandler(ReconciliationException.class)
    ResponseEntity<String> handle(ReconciliationException e) {
        HttpStatus status = switch (e.kind()) {
            case UNKNOWN_DAY, NO_REPORT -> HttpStatus.NOT_FOUND;
            case DAY_CLOSED, DAY_OPEN, CONFLICTING_DUPLICATE -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(e.getMessage());
    }

    private static ReconciliationException unknown(LocalDate businessDate) {
        return new ReconciliationException(Kind.UNKNOWN_DAY, "no reconciliation for " + businessDate);
    }

    /** Neutralises spreadsheet formula prefixes and quotes cells that need it (RFC 4180). */
    static String csvCell(String value) {
        String lead = value.replaceFirst("^[\\p{Cntrl}\\s]+", "");
        boolean formula = !lead.equals(value) || !lead.isEmpty() && "=+-@".indexOf(lead.charAt(0)) >= 0;
        String cell = formula ? "'" + value : value;
        return cell.matches("(?s).*[\",\r\n].*") ? '"' + cell.replace("\"", "\"\"") + '"' : cell;
    }
}
