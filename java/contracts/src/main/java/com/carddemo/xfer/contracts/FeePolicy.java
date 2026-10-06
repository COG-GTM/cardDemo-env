package com.carddemo.xfer.contracts;

import java.math.BigDecimal;

public interface FeePolicy {

    FeeResult apply(BigDecimal amount, FeeRule rule);
}
