package com.carddemo.xferfee.contracts;

import java.math.BigDecimal;

/** Computes the transfer fee for an amount under a fee rule (BR-07 to BR-10). */
public interface FeePolicy {

    FeeResult apply(BigDecimal amount, FeeRule rule);
}
