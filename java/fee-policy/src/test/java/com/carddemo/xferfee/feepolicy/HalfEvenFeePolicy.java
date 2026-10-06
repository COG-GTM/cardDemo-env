package com.carddemo.xferfee.feepolicy;

import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Deliberately wrong (banker's rounding) variant, as in {@code tools/parity/naive_ref.py}. */
final class HalfEvenFeePolicy implements FeePolicy {

    @Override
    public FeeResult apply(BigDecimal amount, FeeRule rule) {
        BigDecimal fee = amount.multiply(rule.feePct()).setScale(2, RoundingMode.HALF_EVEN);
        if (fee.compareTo(rule.feeCap()) > 0) {
            return new FeeResult(rule.feeCap().setScale(2), true);
        }
        return new FeeResult(fee, false);
    }
}
