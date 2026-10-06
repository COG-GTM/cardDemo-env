package com.carddemo.replay;

import java.math.BigDecimal;

/** One XFER-EXTRACT-RECORD (CVXFR01Y). */
record Transfer(String tranId, String tranDt, long srcAcctId, long tgtAcctId, String bookId,
                BigDecimal amount, String cardNum) {
}
