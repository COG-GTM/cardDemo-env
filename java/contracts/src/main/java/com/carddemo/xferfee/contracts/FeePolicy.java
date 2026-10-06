package com.carddemo.xferfee.contracts;

import java.math.BigDecimal;

/** Computes the transfer fee for one amount under one rule (XFERFEE 2100-POST-ONE). */
public interface FeePolicy {

    FeeResult apply(BigDecimal amount, FeeRule rule);
}
