package com.carddemo.xferfee.reconciliation.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ReconControllerTest {

    @Autowired
    MockMvc mvc;

    private void postFee(String date, String id, String book, String amount, String fee) throws Exception {
        mvc.perform(post("/recon/" + date + "/posted").contentType(MediaType.APPLICATION_JSON).content("""
                {"tranId":"%s","tranDate":"2024-06-20","sourceAccountId":1,"targetAccountId":2,
                 "bookId":"%s","amount":%s,"feePct":0.015,"feeAmount":%s,"capApplied":false,
                 "ruleEffectiveDate":"2024-06-15"}""".formatted(id, book, amount, fee)))
                .andExpect(status().isAccepted());
    }

    @Test
    void perBookTotalsAndLegacyReport() throws Exception {
        String date = "2024-06-30";
        postFee(date, "TRN0000000000002", "RETAIL", "100.00", "1.50");
        postFee(date, "TRN0000000000003", "INSTL", "1000.00", "5.00");
        mvc.perform(get("/recon/" + date + "/report")).andExpect(status().isConflict());

        mvc.perform(post("/recon/" + date + "/close").param("postingRc", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mvc.perform(get("/recon/" + date))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.books[0].bookId").value("INSTL"))
                .andExpect(jsonPath("$.books[1].bookId").value("RETAIL"))
                .andExpect(jsonPath("$.grandTotal.count").value(2))
                .andExpect(jsonPath("$.grandTotal.fee").value(6.50));

        mvc.perform(get("/recon/" + date + "/report"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Legacy-Return-Code", "0"))
                .andExpect(content().string("""
                         TRANSFER FEE RECONCILIATION
                         TRANSACTION       DATE       BOOK       AMOUNT          FEE
                         TRN0000000000002 2024-06-20 RETAIL           100.00          1.50
                         BOOK RETAIL     SUBTOTAL AMOUNT       100.00  FEE         1.50
                         TRN0000000000003 2024-06-21 INSTL           1000.00          5.00
                         BOOK INSTL      SUBTOTAL AMOUNT      1000.00  FEE         5.00
                         GRAND TOTAL COUNT         2 AMOUNT      1100.00  FEE         6.50
                        """.replace("2024-06-21", "2024-06-20")));

        mvc.perform(get("/recon/" + date + "/books.csv"))
                .andExpect(content().string("book_id,count,amount,fee\nINSTL,1,1000.00,5.00\nRETAIL,1,100.00,1.50\n"));
    }

    @Test
    void skippedDayHasNoReportAndUnknownDayIs404() throws Exception {
        mvc.perform(post("/recon/2024-07-01/close").param("postingRc", "8"))
                .andExpect(jsonPath("$.status").value("SKIPPED"));
        mvc.perform(get("/recon/2024-07-01/report")).andExpect(status().isNotFound());
        mvc.perform(get("/recon/2099-01-01")).andExpect(status().isNotFound());
    }

    @Test
    void csvCellsCannotCarrySpreadsheetFormulas() {
        assertThat(ReconController.csvCell("RETAIL")).isEqualTo("RETAIL");
        assertThat(ReconController.csvCell("=HYPERLINK(1)")).isEqualTo("'=HYPERLINK(1)");
        assertThat(ReconController.csvCell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(ReconController.csvCell("\n=HYPERLINK(1)")).isEqualTo("\"'\n=HYPERLINK(1)\"");
        assertThat(ReconController.csvCell(" \t=1+1")).isEqualTo("' \t=1+1");
        assertThat(ReconController.csvCell("A,\"B")).isEqualTo("\"A,\"\"B\"");
    }
}
