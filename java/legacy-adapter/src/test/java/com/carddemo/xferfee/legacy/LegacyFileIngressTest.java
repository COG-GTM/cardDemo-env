package com.carddemo.xferfee.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.legacy.codec.CodecOptions;
import com.carddemo.xferfee.legacy.codec.SignStyle;
import com.carddemo.xferfee.legacy.ingress.InMemoryTransactionPublisher;
import com.carddemo.xferfee.legacy.ingress.IngressBatch;
import com.carddemo.xferfee.legacy.ingress.LegacyFileIngress;
import com.carddemo.xferfee.legacy.ingress.LegacyInputFiles;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyFileIngressTest {

    private final LegacyFileIngress ingress = new LegacyFileIngress(CodecOptions.GNUCOBOL_OUTPUT);

    @Test
    void readsTheThreeInputDatasets() throws IOException {
        IngressBatch batch = ingress.read(LegacyInputFiles.in(Fixtures.XFERFEE.resolve("default/input")));

        assertThat(batch.transactions()).hasSize(4);
        DailyTransaction first = batch.transactions().get(1);
        assertThat(first.tranId()).isEqualTo("TRN0000000000002");
        assertThat(first.typeCode()).isEqualTo("08");
        assertThat(first.amount()).isEqualTo(new BigDecimal("100.00"));
        assertThat(first.description().substring(13, 24)).isEqualTo("00000000002");
        assertThat(batch.transactionImages().get(1).signStyle("DALYTRAN-AMT")).isEqualTo(SignStyle.OVERPUNCH);

        assertThat(batch.xrefs()).isNotEmpty().allSatisfy(x -> assertThat(x.cardNumber()).hasSize(16));
        assertThat(batch.accounts().first(1)).get().extracting(Account::groupId).isEqualTo("RETAIL");
    }

    @Test
    void publishesEveryTransactionToCardTransactionsInFileOrder() throws IOException {
        IngressBatch batch = ingress.read(LegacyInputFiles.in(Fixtures.XFERFEE.resolve("non_transfer/input")));
        InMemoryTransactionPublisher publisher = new InMemoryTransactionPublisher();

        int published = ingress.publish(batch, publisher);

        assertThat(published).isEqualTo(batch.transactions().size());
        assertThat(publisher.published()).allSatisfy(p -> {
            assertThat(p.topic()).isEqualTo(LegacyFileIngress.CARD_TRANSACTIONS_TOPIC);
            assertThat(p.key()).isEqualTo(p.event().transaction().cardNumber());
        });
        assertThat(publisher.published()).extracting(p -> p.event().sequence()).containsExactly(1, 2, 3, 4);
        assertThat(publisher.published()).extracting(p -> p.event().transaction().typeCode())
                .contains("08").anyMatch(code -> !code.equals("08"));
    }

    @Test
    void loadsAccountsAndXrefs() throws IOException {
        IngressBatch batch = ingress.read(LegacyInputFiles.in(Fixtures.XFERFEE.resolve("default/input")));
        List<Account> accounts = new ArrayList<>();
        List<CardXref> xrefs = new ArrayList<>();

        ingress.load(batch, accounts::add, xrefs::add);

        assertThat(accounts).isEqualTo(batch.accounts().accounts());
        assertThat(xrefs).isEqualTo(batch.xrefs());
    }
}
