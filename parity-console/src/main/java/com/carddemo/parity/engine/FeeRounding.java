package com.carddemo.parity.engine;

import java.math.RoundingMode;

/** How the Java side rounds the fee. COBOL {@code COMPUTE ... ROUNDED} rounds half away from zero. */
public enum FeeRounding {
    COBOL_ROUNDED(RoundingMode.HALF_UP),
    BREAK_IT_HALF_EVEN(RoundingMode.HALF_EVEN);

    private final RoundingMode mode;

    FeeRounding(RoundingMode mode) {
        this.mode = mode;
    }

    public RoundingMode mode() {
        return mode;
    }

    public static FeeRounding of(boolean breakIt) {
        return breakIt ? BREAK_IT_HALF_EVEN : COBOL_ROUNDED;
    }
}
