package com.carddemo.xferfee.parity.interim;

import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** BR-08 half-up rounding, BR-09 cap after rounding when strictly greater, BR-10 zero amount. */
public class InterimFeePolicy implements FeePolicy {

    @Override
    public FeeResult apply(BigDecimal amount, FeeRule rule) {
        if (amount.signum() == 0) {
            return new FeeResult(BigDecimal.ZERO.setScale(2), false);
        }
        BigDecimal fee = amount.multiply(rule.feePct()).setScale(2, RoundingMode.HALF_UP);
        if (fee.compareTo(rule.feeCap()) > 0) {
            return new FeeResult(rule.feeCap().setScale(2, RoundingMode.HALF_UP), true);
        }
        return new FeeResult(fee, false);
    }
}
