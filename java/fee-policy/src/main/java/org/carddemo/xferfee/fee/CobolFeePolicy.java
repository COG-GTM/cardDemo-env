package org.carddemo.xferfee.fee;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import org.carddemo.xferfee.contracts.FeePolicy;
import org.carddemo.xferfee.contracts.FeeResult;
import org.carddemo.xferfee.contracts.FeeRule;

/**
 * XFERFEE fee arithmetic, bit-for-bit with the COBOL:
 *
 * <pre>
 * IF XFR-TRAN-AMT NOT = 0
 *     COMPUTE WS-FEE-AMT ROUNDED = XFR-TRAN-AMT * WS-FEE-PCT
 *     IF WS-FEE-AMT &gt; WS-FEE-CAP
 *         MOVE WS-FEE-CAP TO WS-FEE-AMT
 *         MOVE "Y" TO XFE-CAP-APPLIED
 * </pre>
 *
 * <ul>
 *   <li>BR-07: {@code ROUNDED} is half-up (away from zero) to 2 dp, never half-even, never {@code double}.</li>
 *   <li>BR-08: the cap is compared against the already-rounded fee.</li>
 *   <li>BR-09: the cap applies only when the rounded fee is strictly greater than the cap.</li>
 *   <li>BR-10: a zero amount gives a zero fee with no cap, but the rule must still exist.</li>
 * </ul>
 *
 * WS-FEE-AMT is {@code S9(09)V99} with no {@code ON SIZE ERROR}, so the rounded product keeps only its low-order
 * nine integer digits before the cap compare.
 */
public final class CobolFeePolicy implements FeePolicy {

    static final int FEE_SCALE = 2;
    static final RoundingMode COBOL_ROUNDED = RoundingMode.HALF_UP;
    private static final BigDecimal WS_FEE_AMT_MODULUS = BigDecimal.TEN.pow(9);
    private static final BigDecimal ZERO_FEE = BigDecimal.ZERO.setScale(FEE_SCALE);

    @Override
    public FeeResult apply(BigDecimal amount, FeeRule rule) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(rule, "rule");
        if (amount.signum() == 0) {
            return new FeeResult(ZERO_FEE, FeeResult.CAP_NOT_APPLIED);
        }
        BigDecimal fee = amount.multiply(rule.feePct())
                .setScale(FEE_SCALE, COBOL_ROUNDED)
                .remainder(WS_FEE_AMT_MODULUS);
        BigDecimal cap = rule.feeCap().setScale(FEE_SCALE, RoundingMode.DOWN);
        if (fee.compareTo(cap) > 0) {
            return new FeeResult(cap, FeeResult.CAP_APPLIED);
        }
        return new FeeResult(fee, FeeResult.CAP_NOT_APPLIED);
    }
}
