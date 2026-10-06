package com.carddemo.contracts;

import java.math.BigDecimal;

public record FeeResult(BigDecimal feeAmount, boolean capApplied) {
}
