package com.carddemo.xferfee.contracts;

import java.math.BigDecimal;

/** Computes the fee for one transfer amount under a resolved rule (BR-08..BR-10). */
public interface FeePolicy {

    FeeResult apply(BigDecimal amount, FeeRule rule);
}
