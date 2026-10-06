package com.carddemo.parity;

import com.carddemo.contracts.FeePolicy;
import com.carddemo.contracts.FeeResult;
import com.carddemo.contracts.FeeRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * Stand-in for the fee-policy library (COG-1235) so posting can be replayed on its own:
 * COMPUTE ROUNDED (half-up), round then cap, cap only when strictly greater, zero amount → zero fee.
 */
@Component
public class InterimFeePolicy implements FeePolicy {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    @Override
    public FeeResult apply(BigDecimal amount, FeeRule rule) {
        if (amount.signum() == 0) {
            return new FeeResult(ZERO, false);
        }
        BigDecimal fee = amount.multiply(rule.feePct()).setScale(2, RoundingMode.HALF_UP);
        if (fee.compareTo(rule.feeCap()) > 0) {
            return new FeeResult(rule.feeCap().setScale(2, RoundingMode.UNNECESSARY), true);
        }
        return new FeeResult(fee, false);
    }
}
