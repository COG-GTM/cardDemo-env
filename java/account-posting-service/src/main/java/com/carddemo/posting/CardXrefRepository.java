package com.carddemo.posting;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CardXrefRepository {

    private final JdbcTemplate jdbc;

    public CardXrefRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String cardNum, long custId, long acctId) {
        jdbc.update("INSERT INTO CARD_XREF (CARD_NUM, CUST_ID, ACCT_ID) VALUES (?, ?, ?)", cardNum, custId, acctId);
    }

    public Optional<Long> accountForCard(String cardNum) {
        return jdbc.queryForList("SELECT ACCT_ID FROM CARD_XREF WHERE CARD_NUM = ?", Long.class, cardNum)
                .stream().findFirst();
    }
}
