package com.carddemo.contracts;

import java.math.BigDecimal;

/** Computes the transfer fee for an amount under a rule (implemented by fee-policy). */
public interface FeePolicy {

    FeeResult apply(BigDecimal amount, FeeRule rule);
}
