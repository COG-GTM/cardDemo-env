package com.carddemo.xferfee.feepolicy;

import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * XFERFEE {@code 2100-POST-ONE} fee arithmetic: a zero amount is never priced (BR-11), otherwise
 * {@code COMPUTE WS-FEE-AMT ROUNDED = AMT * PCT} (half away from zero to cents, BR-09) and the cap
 * replaces the rounded fee only when the fee is strictly greater (BR-10).
 */
public final class CobolFeePolicy implements FeePolicy {

    static final int FEE_SCALE = 2;

    @Override
    public FeeResult apply(BigDecimal amount, FeeRule rule) {
        BigDecimal zero = BigDecimal.ZERO.setScale(FEE_SCALE);
        if (amount.signum() == 0) {
            return new FeeResult(zero, false);
        }
        BigDecimal fee = amount.multiply(rule.feePct()).setScale(FEE_SCALE, RoundingMode.HALF_UP);
        if (fee.compareTo(rule.feeCap()) > 0) {
            return new FeeResult(rule.feeCap().setScale(FEE_SCALE, RoundingMode.HALF_UP), true);
        }
        return new FeeResult(fee, false);
    }
}
