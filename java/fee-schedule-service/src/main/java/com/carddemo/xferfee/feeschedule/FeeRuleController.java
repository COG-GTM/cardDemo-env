package com.carddemo.xferfee.feeschedule;

import com.carddemo.xferfee.contracts.FeeRule;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fee-rules")
public class FeeRuleController {

    private final JdbcFeeSchedule schedule;

    public FeeRuleController(JdbcFeeSchedule schedule) {
        this.schedule = schedule;
    }

    /** 200 with the effective rule, 404 when none applies to the business date. */
    @GetMapping("/effective")
    public ResponseEntity<FeeRule> effective(
            @RequestParam String book,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.of(schedule.effectiveRule(book, date));
    }

    @GetMapping
    public List<FeeRule> all() {
        return schedule.rules();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FeeRule create(@RequestBody FeeRule rule) {
        return schedule.addRule(rule);
    }

    @ExceptionHandler(InvalidFeeRuleException.class)
    ProblemDetail invalid(InvalidFeeRuleException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(OverlappingFeeRuleException.class)
    ProblemDetail overlapping(OverlappingFeeRuleException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("existing", e.existing());
        return problem;
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicate(DuplicateKeyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "a fee rule with this book and effDt already exists");
    }

    @ExceptionHandler(AmbiguousFeeRuleException.class)
    ProblemDetail ambiguous(AmbiguousFeeRuleException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("matches", e.matches());
        return problem;
    }
}
