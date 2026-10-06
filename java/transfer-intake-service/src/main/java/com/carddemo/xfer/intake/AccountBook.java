package com.carddemo.xfer.intake;

/**
 * The CVACT01Y fields CBXFR01C reads.
 *
 * @param accountId ACCT-ID, 9(11)
 * @param groupId   ACCT-GROUP-ID, X(10); the account's book
 */
public record AccountBook(String accountId, String groupId) {
}
