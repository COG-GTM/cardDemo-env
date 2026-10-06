package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.CardXref;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public class CardXrefRepository {

    private final JdbcTemplate jdbc;

    public CardXrefRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CardXref xref) {
        jdbc.update("INSERT INTO CARD_XREF (CARD_NUM, CUST_ID, ACCT_ID) VALUES (?, ?, ?)",
                xref.cardNumber(), xref.customerId(), xref.accountId());
    }

    public Optional<Long> findAccountId(String cardNumber) {
        List<Long> ids = jdbc.queryForList(
                "SELECT ACCT_ID FROM CARD_XREF WHERE CARD_NUM = ?", Long.class, cardNumber);
        return ids.stream().findFirst();
    }
}
