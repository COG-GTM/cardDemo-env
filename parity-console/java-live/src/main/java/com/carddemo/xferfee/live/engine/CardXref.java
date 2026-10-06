package com.carddemo.xferfee.live.engine;

/** Card cross-reference row (copybook CVACT03Y). */
public record CardXref(String cardNum, long custId, long acctId) {
}
