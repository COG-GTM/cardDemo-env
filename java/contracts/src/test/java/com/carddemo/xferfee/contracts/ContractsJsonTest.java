package com.carddemo.xferfee.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ContractsJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void moneyAndDatesSerializeAsStringsAndRoundTrip() throws Exception {
        TransferPosted posted = new TransferPosted(
                "TRN0000000000002", LocalDate.of(2024, 6, 20), 1L, 2L, "RETAIL",
                new BigDecimal("100.00"), new BigDecimal("0.015000"), new BigDecimal("1.50"),
                false, LocalDate.of(2024, 6, 15));

        String json = mapper.writeValueAsString(posted);
        JsonNode tree = mapper.readTree(json);

        assertThat(tree.get("amount").isTextual()).isTrue();
        assertThat(tree.get("amount").asText()).isEqualTo("100.00");
        assertThat(tree.get("feePct").asText()).isEqualTo("0.015000");
        assertThat(tree.get("tranDate").asText()).isEqualTo("2024-06-20");
        assertThat(mapper.readValue(json, TransferPosted.class)).isEqualTo(posted);
    }

    @Test
    void requestedAndRuleRoundTrip() throws Exception {
        TransferRequested requested = new TransferRequested(
                "TRN0000000000003", LocalDate.of(2024, 6, 21), 6L, 7L, "INSTL",
                new BigDecimal("1000.00"), "4000000000000006");
        FeeRule rule = new FeeRule("INSTL", new BigDecimal("0.005000"), new BigDecimal("500.00"),
                LocalDate.of(2020, 1, 1), LocalDate.of(9999, 12, 31));

        assertThat(mapper.readValue(mapper.writeValueAsString(requested), TransferRequested.class))
                .isEqualTo(requested);
        assertThat(mapper.readTree(mapper.writeValueAsString(rule)).get("feeCap").asText())
                .isEqualTo("500.00");
    }
}
