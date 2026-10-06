package com.carddemo.xferfee.parity.interim;

import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Replay-only stand-in until fee-policy (COG-1235) registers a {@link FeePolicy} bean: amount x
 * rate rounded half-up to the cent, then capped when strictly greater (BR-08..BR-10).
 */
class InterimFeePolicy implements FeePolicy {

    @Override
    public FeeResult apply(BigDecimal amount, FeeRule rule) {
        BigDecimal fee = amount.multiply(rule.feePct()).setScale(2, RoundingMode.HALF_UP);
        if (fee.compareTo(rule.feeCap()) > 0) {
            return new FeeResult(rule.feeCap().setScale(2), true);
        }
        return new FeeResult(fee, false);
    }
}
