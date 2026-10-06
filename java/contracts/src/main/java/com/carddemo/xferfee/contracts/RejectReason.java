package com.carddemo.xferfee.contracts;

/** Why a transfer was rejected; legacy RC mapping in brackets. */
public enum RejectReason {
    /** Card not in the cross-reference (BR-05, RC 4). */
    UNMATCHED_CARD,
    /** Card resolved but source account missing from the master (BR-05, RC 4). */
    UNKNOWN_SOURCE_ACCOUNT,
    /** Source or target account missing at posting time (BR-12, RC 8). */
    UNKNOWN_ACCOUNT,
    /** No effective CTL_XFER_PARM rule for book and date (BR-07, RC 8). */
    NO_FEE_RULE,
    /** TRAN_ID already in the ledger (BR-14, RC 8). */
    DUPLICATE_TRAN_ID,
    /** Any other posting failure (SQL error equivalent, RC 8). */
    POSTING_ERROR
}
