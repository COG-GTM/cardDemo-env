package com.carddemo.xferfee.feepolicy;

import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * XFERFEE {@code 2100-POST-ONE} fee arithmetic (BR-08..BR-10).
 *
 * <ul>
 *   <li>BR-08: {@code COMPUTE WS-FEE-AMT ROUNDED = XFR-TRAN-AMT * WS-FEE-PCT} is half-up to cents.</li>
 *   <li>BR-09: the cap is applied to the rounded fee; only {@code fee > cap} flags the cap.</li>
 *   <li>BR-10: a zero amount yields a zero fee, but the caller must already hold a rule (BR-07).</li>
 * </ul>
 */
public final class HalfUpFeePolicy implements FeePolicy {

    /** {@code WS-FEE-AMT} / {@code XFE-FEE-AMT} are {@code PIC S9(09)V99}. */
    public static final int FEE_SCALE = 2;

    private static final BigDecimal ZERO_FEE = BigDecimal.ZERO.setScale(FEE_SCALE);

    @Override
    public FeeResult apply(BigDecimal amount, FeeRule rule) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(rule, "rule");
        if (amount.signum() == 0) {
            return new FeeResult(ZERO_FEE, false);
        }
        BigDecimal fee = amount.multiply(rule.feePct()).setScale(FEE_SCALE, RoundingMode.HALF_UP);
        if (fee.compareTo(rule.feeCap()) > 0) {
            // MOVE WS-FEE-CAP TO WS-FEE-AMT: a MOVE into V99 truncates extra decimals.
            return new FeeResult(rule.feeCap().setScale(FEE_SCALE, RoundingMode.DOWN), true);
        }
        return new FeeResult(fee, false);
    }
}
