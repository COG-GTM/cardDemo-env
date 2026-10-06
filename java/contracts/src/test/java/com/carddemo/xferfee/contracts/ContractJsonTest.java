package com.carddemo.xferfee.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ContractJsonTest {

    @Test
    void moneyIsAPlainStringAndDatesAreIso() throws Exception {
        TransferPosted posted = new TransferPosted(
                "TRN0000000000002", LocalDate.of(2024, 6, 20), 1, 2, "RETAIL",
                new BigDecimal("100.00"), new BigDecimal("0.015000"), new BigDecimal("1.50"),
                false, LocalDate.of(2024, 6, 15));

        String json = ContractJson.mapper().writeValueAsString(posted);

        assertEquals("{\"tranId\":\"TRN0000000000002\",\"tranDate\":\"2024-06-20\","
                + "\"sourceAccountId\":1,\"targetAccountId\":2,\"bookId\":\"RETAIL\","
                + "\"amount\":\"100.00\",\"feePct\":\"0.015000\",\"feeAmount\":\"1.50\","
                + "\"capApplied\":false,\"ruleEffectiveDate\":\"2024-06-15\"}", json);
        assertEquals(posted, ContractJson.mapper().readValue(json, TransferPosted.class));
    }

    @Test
    void ruleWindowIsHalfOpen() {
        FeeRule rule = new FeeRule("RETAIL", new BigDecimal("0.012500"), new BigDecimal("25.00"),
                LocalDate.of(2020, 1, 1), LocalDate.of(2024, 6, 15));

        assertEquals(true, rule.appliesOn(LocalDate.of(2020, 1, 1)));
        assertEquals(true, rule.appliesOn(LocalDate.of(2024, 6, 14)));
        assertEquals(false, rule.appliesOn(LocalDate.of(2024, 6, 15)));
    }
}
