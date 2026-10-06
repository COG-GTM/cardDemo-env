package com.carddemo.xfer.contracts;

import java.math.BigDecimal;

/** Fee computed for one transfer; {@code capApplied} mirrors XFE-CAP-APPLIED. */
public record FeeResult(BigDecimal feeAmt, boolean capApplied) {
}
