package com.carddemo.xferfee.live.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fee arithmetic from XFERFEE 2100-POST-ONE.
 *
 * <p>BR-08: fee = amount x rate, {@code COMPUTE ... ROUNDED} = HALF_UP to the cent.
 * BR-09: cap applied after rounding, flagged only when the rounded fee is strictly greater.
 * BR-10: zero amount gives a zero fee.
 */
public final class FeeCalculator {

    private static final int CENTS = 2;

    private FeeCalculator() {
    }

    public static Fee compute(BigDecimal amount, FeeRule rule, RoundingMode rounding) {
        if (amount.signum() == 0) {
            return new Fee(BigDecimal.ZERO.setScale(CENTS), false);
        }
        BigDecimal fee = amount.multiply(rule.feePct()).setScale(CENTS, rounding);
        BigDecimal cap = rule.feeCap().setScale(CENTS, RoundingMode.UNNECESSARY);
        if (fee.compareTo(cap) > 0) {
            return new Fee(cap, true);
        }
        return new Fee(fee, false);
    }
}
