package com.carddemo.xferfee.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

/**
 * Account master record (CVACT01Y). Date fields stay as the raw PIC X(10) text so the master
 * round-trips byte-for-byte.
 */
public record Account(
        long accountId,
        String activeStatus,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal currentBalance,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal creditLimit,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cashCreditLimit,
        String openDate,
        String expirationDate,
        String reissueDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal currentCycleCredit,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal currentCycleDebit,
        String addressZip,
        String groupId) {
}
