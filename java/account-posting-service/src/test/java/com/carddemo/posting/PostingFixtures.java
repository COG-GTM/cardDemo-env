package com.carddemo.posting;

import com.carddemo.contracts.Account;
import com.carddemo.contracts.TransferRequested;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

final class PostingFixtures {

    private PostingFixtures() {
    }

    static void reset(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM POSTING_OUTBOX");
        jdbc.update("DELETE FROM XFER_FEE_LEDGER");
        jdbc.update("DELETE FROM CARD_XREF");
        jdbc.update("DELETE FROM ACCOUNT");
    }

    static Account account(long id, String balance) {
        return new Account(id, "Y", new BigDecimal(balance), new BigDecimal("5000.00"), new BigDecimal("1000.00"),
                "2020-01-01", "2030-01-01", "2025-01-01", new BigDecimal("0.00"), new BigDecimal("0.00"),
                "10001", "GROUP1");
    }

    static TransferRequested transfer(String tranId, String date, long source, long target, String book,
            String amount) {
        return new TransferRequested(tranId, LocalDate.parse(date), source, target, book, new BigDecimal(amount),
                "4000000000000001");
    }
}
