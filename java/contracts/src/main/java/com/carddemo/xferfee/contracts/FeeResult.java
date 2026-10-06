package com.carddemo.xferfee.contracts;

import java.math.BigDecimal;

/**
 * Outcome of {@link FeePolicy#apply}.
 *
 * @param feeAmount  fee to charge, scale 2
 * @param capApplied true when the computed fee exceeded {@link FeeRule#feeCap()}
 */
public record FeeResult(BigDecimal feeAmount, boolean capApplied) {
}
