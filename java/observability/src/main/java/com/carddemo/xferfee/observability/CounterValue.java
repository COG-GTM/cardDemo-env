package com.carddemo.xferfee.observability;

/**
 * One SYSOUT counter: {@code label} is the DISPLAY text (e.g. {@code RECORDS READ}), {@code value}
 * the plain decimal value, {@code display} the edited COBOL form and {@code metric} the Micrometer
 * meter that mirrors it.
 */
public record CounterValue(String label, String metric, String value, String display) {
}
