package com.carddemo.xfer.intake;

/**
 * The CVACT03Y fields CBXFR01C reads.
 *
 * @param cardNumber XREF-CARD-NUM, X(16)
 * @param accountId  XREF-ACCT-ID, 9(11)
 */
public record CardCrossReference(String cardNumber, String accountId) {
}
