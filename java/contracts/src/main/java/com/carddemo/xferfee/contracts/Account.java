package com.carddemo.xferfee.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

/** Account master record (copybook CVACT01Y). {@code groupId} is the fee book (ACCT-GROUP-ID). */
public record Account(
        long acctId,
        String activeStatus,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal currBal,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal creditLimit,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cashCreditLimit,
        String openDate,
        String expirationDate,
        String reissueDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal currCycCredit,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal currCycDebit,
        String addrZip,
        String groupId) {
}
