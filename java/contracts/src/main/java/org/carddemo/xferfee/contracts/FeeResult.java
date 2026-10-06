package org.carddemo.xferfee.contracts;

import java.math.BigDecimal;
import java.util.Objects;

/** XFE-FEE-AMT (scale 2) and XFE-CAP-APPLIED ({@code "Y"} or {@code "N"}). */
public record FeeResult(BigDecimal feeAmt, String capApplied) {

    public static final String CAP_APPLIED = "Y";
    public static final String CAP_NOT_APPLIED = "N";

    public FeeResult {
        Objects.requireNonNull(feeAmt, "feeAmt");
        if (!CAP_APPLIED.equals(capApplied) && !CAP_NOT_APPLIED.equals(capApplied)) {
            throw new IllegalArgumentException("capApplied must be Y or N: " + capApplied);
        }
    }

    public boolean isCapApplied() {
        return CAP_APPLIED.equals(capApplied);
    }
}
