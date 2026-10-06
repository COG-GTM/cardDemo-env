package com.carddemo.feeschedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/fee-rules")
public class FeeRuleController {

    private final FeeScheduleService service;

    public FeeRuleController(FeeScheduleService service) {
        this.service = service;
    }

    @GetMapping("/effective")
    public FeeRule effective(
            @RequestParam @NotBlank @Size(max = FeeRule.BOOK_ID_LENGTH) String book,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.effectiveRule(book, date);
    }

    @GetMapping(produces = "application/json")
    public List<FeeRule> all() {
        return service.allRules();
    }

    /** The table in the recorder's db2_after/CTL_XFER_PARM.csv layout. */
    @GetMapping(produces = FeeRuleCsv.MEDIA_TYPE)
    public String allAsCsv() {
        return FeeRuleCsv.write(service.allRules());
    }

    @PostMapping
    public ResponseEntity<FeeRule> add(@Valid @RequestBody FeeRule rule) {
        FeeRule saved = service.addRule(rule);
        URI location = UriComponentsBuilder.fromPath("/fee-rules/effective")
                .queryParam("book", saved.bookId())
                .queryParam("date", saved.effDt())
                .build()
                .toUri();
        return ResponseEntity.created(location).body(saved);
    }
}
