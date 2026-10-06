package com.carddemo.xferfee.shadow;

import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * XFERFEE 2100-POST-ONE fee arithmetic: BR-08 {@code COMPUTE ... ROUNDED} (half-up to the cent), then BR-09 the
 * cap replaces the rounded fee only when the fee is strictly greater. BR-10 (zero amount skips the computation) is
 * the caller's job because the rule lookup still happens.
 */
public final class LegacyFeePolicy implements FeePolicy {

    @Override
    public FeeResult apply(BigDecimal amount, FeeRule rule) {
        BigDecimal fee = amount.multiply(rule.feePct()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cap = rule.feeCap().setScale(2, RoundingMode.UNNECESSARY);
        return fee.compareTo(cap) > 0 ? new FeeResult(cap, true) : new FeeResult(fee, false);
    }
}
