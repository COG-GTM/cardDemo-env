package com.carddemo.xferfee.legacy.ingress;

import com.carddemo.xferfee.contracts.DailyTransaction;

/** Message published to {@code card.transactions}; {@code sequence} is the 1-based DALYTRAN record number. */
public record CardTransactionEvent(int sequence, DailyTransaction transaction) {
}
