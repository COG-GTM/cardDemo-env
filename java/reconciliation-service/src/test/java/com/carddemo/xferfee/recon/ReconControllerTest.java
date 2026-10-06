package com.carddemo.xferfee.recon;

import static com.carddemo.xferfee.recon.LegacyReconStepTest.posted;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.xferfee.contracts.TransferRejected;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReconController.class)
@Import(ReconciliationService.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ReconControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ReconciliationService service;

    @BeforeEach
    void seed() {
        service.onPosted(posted("TRN0000000000001", "RETAIL", "100.00", "1.25"));
        service.onPosted(posted("TRN0000000000002", "INSTL", "1000.00", "5.00"));
        service.onPosted(posted("TRN0000000000003", "RETAIL", "200.00", "2.50"));
        service.onRejected(new TransferRejected(LegacyReconStepTest.DAY, "TRN0000000000004", "RETAIL",
                new BigDecimal("50.00"), "ACCOUNT NOT FOUND"));
    }

    @Test
    void returnsTruePerBookTotals() throws Exception {
        mvc.perform(get("/recon/2024-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2024-06-30"))
                .andExpect(jsonPath("$.books.length()").value(2))
                .andExpect(jsonPath("$.books[0].bookId").value("INSTL"))
                .andExpect(jsonPath("$.books[0].amount").value("1000.00"))
                .andExpect(jsonPath("$.books[1].bookId").value("RETAIL"))
                .andExpect(jsonPath("$.books[1].count").value(2))
                .andExpect(jsonPath("$.books[1].amount").value("300.00"))
                .andExpect(jsonPath("$.books[1].fee").value("3.75"))
                .andExpect(jsonPath("$.grandTotal.count").value(3))
                .andExpect(jsonPath("$.grandTotal.fee").value("8.75"))
                .andExpect(jsonPath("$.rejectedCount").value(1));
    }

    @Test
    void unknownDateIsNotFound() throws Exception {
        mvc.perform(get("/recon/2024-07-01")).andExpect(status().isNotFound());
    }

    @Test
    void legacyReportKeepsFileOrderSubtotals() throws Exception {
        mvc.perform(get("/recon/2024-06-30/legacy-report"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Return-Code", "0"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        " BOOK RETAIL     SUBTOTAL AMOUNT       100.00  FEE         1.25\n")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        " BOOK RETAIL     SUBTOTAL AMOUNT       200.00  FEE         2.50\n")));
    }

    @Test
    void legacyReportSkippedWhenPostingFailed() throws Exception {
        mvc.perform(get("/recon/2024-06-30/legacy-report").param("postingRc", "8"))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("X-Recon-Skipped"));
    }
}
