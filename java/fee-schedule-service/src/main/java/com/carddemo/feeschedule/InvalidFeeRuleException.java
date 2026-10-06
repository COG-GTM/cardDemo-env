package com.carddemo.feeschedule;

public class InvalidFeeRuleException extends RuntimeException {

    public InvalidFeeRuleException(String message) {
        super(message);
    }
}
