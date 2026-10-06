package com.carddemo.xferfee.live.engine;

public enum TransferOutcome {
    /** Transfer posted, ledger row written. */
    POSTED,
    /** Not a transfer (BR-01). */
    IGNORED,
    /** Card or source account unresolved (BR-05). */
    SKIPPED,
    /** Run-stopping condition (BR-07, BR-12, BR-14); nothing committed. */
    REJECTED
}
