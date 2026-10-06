package com.carddemo.xfer.feepolicy;

import com.carddemo.xfer.contracts.FeePolicy;
import com.carddemo.xfer.contracts.FeeResult;
import com.carddemo.xfer.contracts.FeeRule;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * XFERFEE 2100-POST-ONE: {@code COMPUTE WS-FEE-AMT ROUNDED = XFR-TRAN-AMT * WS-FEE-PCT},
 * then cap when strictly greater; a zero amount skips the computation.
 */
public final class CobolFeePolicy implements FeePolicy {

    private static final int FEE_SCALE = 2;

    @Override
    public FeeResult apply(BigDecimal amount, FeeRule rule) {
        BigDecimal fee = BigDecimal.ZERO.setScale(FEE_SCALE);
        if (amount.signum() == 0) {
            return new FeeResult(fee, false);
        }
        // COBOL ROUNDED is nearest-away-from-zero, which is BigDecimal HALF_UP.
        fee = amount.multiply(rule.feePct()).setScale(FEE_SCALE, RoundingMode.HALF_UP);
        if (fee.compareTo(rule.feeCap()) > 0) {
            return new FeeResult(rule.feeCap().setScale(FEE_SCALE), true);
        }
        return new FeeResult(fee, false);
    }
}
