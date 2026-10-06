package com.carddemo.xfer.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class XferJsonTest {

    @Test
    void moneyTravelsAsStringAndRoundTrips() {
        TransferRequested event = new TransferRequested("run", 1, "TRN1", "2024-06-20", 1, 2,
                "RETAIL", new BigDecimal("100.00"), "4000000000000001");
        String json = XferJson.write(event);
        assertTrue(json.contains("\"type\":\"TransferRequested\""), json);
        assertTrue(json.contains("\"tranAmt\":\"100.00\""), json);
        assertEquals(event, XferJson.read(json));
    }
}
