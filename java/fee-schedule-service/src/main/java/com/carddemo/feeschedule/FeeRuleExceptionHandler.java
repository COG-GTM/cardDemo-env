package com.carddemo.feeschedule;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class FeeRuleExceptionHandler {

    @ExceptionHandler(FeeRuleNotFoundException.class)
    ProblemDetail notFound(FeeRuleNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Fee rule not found", e);
    }

    @ExceptionHandler(AmbiguousFeeRuleException.class)
    ProblemDetail ambiguous(AmbiguousFeeRuleException e) {
        return problem(HttpStatus.CONFLICT, "Ambiguous fee rule", e);
    }

    @ExceptionHandler(OverlappingFeeRuleException.class)
    ProblemDetail overlapping(OverlappingFeeRuleException e) {
        return problem(HttpStatus.CONFLICT, "Overlapping fee rule", e);
    }

    @ExceptionHandler(InvalidFeeRuleException.class)
    ProblemDetail invalid(InvalidFeeRuleException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid fee rule", e);
    }

    private static ProblemDetail problem(HttpStatus status, String title, RuntimeException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        detail.setTitle(title);
        return detail;
    }
}
