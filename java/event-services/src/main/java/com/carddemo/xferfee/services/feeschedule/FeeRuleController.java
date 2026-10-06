package com.carddemo.xferfee.services.feeschedule;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fee-rules")
public class FeeRuleController {

    private final FeeSchedule schedule;

    public FeeRuleController(FeeSchedule schedule) {
        this.schedule = schedule;
    }

    @GetMapping
    public List<FeeRule> all() {
        return schedule.rules();
    }

    /** 404 is the BR-07 "no rule" outcome; the caller decides whether that aborts or dead-letters. */
    @GetMapping("/effective")
    public ResponseEntity<FeeRule> effective(@RequestParam String bookId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.of(schedule.effectiveRule(bookId, date));
    }

    @PutMapping
    public List<FeeRule> replace(@RequestBody List<FeeRule> rules) {
        schedule.seed(rules);
        return schedule.rules();
    }
}
