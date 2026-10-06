package com.carddemo.xferfee.contracts;

/** Card cross-reference record (CVACT03Y). */
public record CardXref(
        String cardNumber,
        long customerId,
        long accountId) {
}
