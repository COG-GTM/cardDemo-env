package com.carddemo.xferfee.live.engine;

import java.math.BigDecimal;

public record Fee(BigDecimal amount, boolean capApplied) {
}
