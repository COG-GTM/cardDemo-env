package com.carddemo.observability;

/** Alert raised for a non-zero step return code. */
public record Alert(String severity, String step, String program, int returnCode, String summary) {
}
