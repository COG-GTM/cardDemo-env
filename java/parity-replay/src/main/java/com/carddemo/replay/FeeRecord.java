package com.carddemo.replay;

import java.math.BigDecimal;

/** The XFER-FEE-RECORD fields CBXFR03C totals. */
record FeeRecord(String tranId, String bookId, BigDecimal amount, BigDecimal fee) {
}
