package com.carddemo.xferfee.parity.interim;

import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** XFERFEE 2100-POST-ONE fee arithmetic (BR-08..BR-10). Replaced by fee-policy (COG-1235). */
class InterimFeePolicy implements FeePolicy {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    @Override
    public FeeResult apply(BigDecimal amount, FeeRule rule) {
        if (amount.signum() == 0) {
            return new FeeResult(ZERO, false);
        }
        // COMPUTE ... ROUNDED: nearest, half away from zero
        BigDecimal fee = amount.multiply(rule.feePct()).setScale(2, RoundingMode.HALF_UP);
        if (fee.compareTo(rule.feeCap()) > 0) {
            return new FeeResult(rule.feeCap().setScale(2, RoundingMode.UNNECESSARY), true);
        }
        return new FeeResult(fee, false);
    }
}
