package com.carddemo.contracts;

/** Why CBXFR01C dropped a type-08 transaction from the transfer extract. */
public enum RejectReason {
    CARD_NOT_FOUND,
    ACCOUNT_NOT_FOUND
}
