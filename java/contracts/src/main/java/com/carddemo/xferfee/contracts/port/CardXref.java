package com.carddemo.xferfee.contracts.port;

/** One CVACT03Y card cross-reference record. */
public record CardXref(String cardNumber, long customerId, long accountId) {
}
