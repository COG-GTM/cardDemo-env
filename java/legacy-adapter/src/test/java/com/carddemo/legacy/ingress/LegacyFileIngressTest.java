package com.carddemo.legacy.ingress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.legacy.Estate;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyFileIngressTest {
  private final List<String> calls = new ArrayList<>();
  private final List<DailyTransaction> published = new ArrayList<>();
  private final List<Account> accounts = new ArrayList<>();
  private final List<CardXref> xrefs = new ArrayList<>();

  private final ReferenceDataLoader loader =
      new ReferenceDataLoader() {
        @Override
        public void loadAccounts(List<Account> a) {
          calls.add("accounts");
          accounts.addAll(a);
        }

        @Override
        public void loadCardXrefs(List<CardXref> x) {
          calls.add("xrefs");
          xrefs.addAll(x);
        }
      };

  @Test
  void loadsReferenceDataThenPublishesEveryDailyTransaction() throws Exception {
    Path input = Estate.fixture("default").resolve("input");
    LegacyFileIngress.Summary summary =
        new LegacyFileIngress(Estate.copybooks(), t -> { calls.add("publish"); published.add(t); }, loader)
            .ingest(input);

    assertEquals(Files.size(input.resolve("ACCTDATA.PS")) / 300, summary.accounts());
    assertEquals(Files.size(input.resolve("CARDXREF.PS")) / 50, summary.cardXrefs());
    assertEquals(Files.size(input.resolve("DALYTRAN.PS")) / 350, summary.transactions());
    assertEquals(List.of("accounts", "xrefs"), calls.subList(0, 2));
    assertTrue(calls.subList(2, calls.size()).stream().allMatch("publish"::equals));
    assertEquals(summary.transactions(), published.size());

    DailyTransaction first = published.get(0);
    assertFalse(first.tranId().isBlank());
    assertEquals(16, first.cardNum().length());
    assertTrue(published.stream().anyMatch(t -> t.typeCd().equals("08")), "fixture has transfer transactions");
    assertTrue(published.stream().allMatch(t -> t.amount().scale() == 2));

    for (DailyTransaction t : published) {
      assertTrue(xrefs.stream().anyMatch(x -> x.cardNum().equals(t.cardNum())), "xref for " + t.cardNum());
    }
    for (CardXref x : xrefs) {
      assertTrue(accounts.stream().anyMatch(a -> a.acctId() == x.acctId()), "account for " + x.acctId());
    }
  }

  @Test
  void jsonLinesSinkWritesPlainDecimals(@TempDir Path out) throws Exception {
    try (JsonLinesSink sink = new JsonLinesSink(out)) {
      sink.publish(new DailyTransaction("T1", "08", 1, "S", "D", new BigDecimal("-12.50"), 1, "M", "C", "Z", "4111", "o", "p"));
      sink.loadAccounts(List.of());
      sink.loadCardXrefs(List.of(new CardXref("4111", 9, 7)));
    }
    String line = Files.readAllLines(out.resolve("card.transactions.jsonl")).get(0);
    assertTrue(line.contains("\"tranId\":\"T1\"") && line.contains("\"amount\":-12.50"), line);
    assertEquals(List.of("{\"cardNum\":\"4111\",\"custId\":9,\"acctId\":7}"), Files.readAllLines(out.resolve("card-xref.jsonl")));
    assertEquals(List.of(), Files.readAllLines(out.resolve("accounts.jsonl")));
  }
}
