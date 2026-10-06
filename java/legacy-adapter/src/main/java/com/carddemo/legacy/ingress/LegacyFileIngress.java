package com.carddemo.legacy.ingress;

import com.carddemo.legacy.codec.CopybookCodec;
import com.carddemo.legacy.codec.CopybookRecord;
import com.carddemo.legacy.codec.SignEncoding;
import com.carddemo.legacy.copybook.CopybookParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Reads the three nightly mainframe extracts ({@code DALYTRAN.PS}, {@code ACCTDATA.PS},
 * {@code CARDXREF.PS}) and hands them to the Java side: reference data is loaded first so
 * consumers of {@code card.transactions} can resolve accounts and cards for every event.
 */
public final class LegacyFileIngress {
  public static final String DAILY_TRANSACTIONS = "DALYTRAN.PS";
  public static final String ACCOUNTS = "ACCTDATA.PS";
  public static final String CARD_XREF = "CARDXREF.PS";

  private final CopybookParser copybooks;
  private final TransactionPublisher publisher;
  private final ReferenceDataLoader referenceData;

  public LegacyFileIngress(
      CopybookParser copybooks, TransactionPublisher publisher, ReferenceDataLoader referenceData) {
    this.copybooks = copybooks;
    this.publisher = publisher;
    this.referenceData = referenceData;
  }

  /** Summary of one ingress run. */
  public record Summary(int accounts, int cardXrefs, int transactions) {}

  public Summary ingest(Path inputDirectory) throws IOException {
    List<Account> accounts = read(inputDirectory.resolve(ACCOUNTS), Account.COPYBOOK, Account::from);
    List<CardXref> xrefs = read(inputDirectory.resolve(CARD_XREF), CardXref.COPYBOOK, CardXref::from);
    List<DailyTransaction> transactions =
        read(inputDirectory.resolve(DAILY_TRANSACTIONS), DailyTransaction.COPYBOOK, DailyTransaction::from);
    referenceData.loadAccounts(accounts);
    referenceData.loadCardXrefs(xrefs);
    transactions.forEach(publisher::publish);
    return new Summary(accounts.size(), xrefs.size(), transactions.size());
  }

  /**
   * Account master records with their source bytes, for {@code AcctDataXferEgress} to overlay
   * updates on so unchanged fields are written back exactly as the mainframe produced them.
   */
  public List<CopybookRecord> accountImages(Path inputDirectory) throws IOException {
    return read(inputDirectory.resolve(ACCOUNTS), Account.COPYBOOK, r -> r);
  }

  private <T> List<T> read(
      Path file, String copybook, Function<CopybookRecord, T> mapper)
      throws IOException {
    CopybookCodec codec = new CopybookCodec(copybooks.layout(copybook), SignEncoding.OVERPUNCH);
    return codec.decodeAll(Files.readAllBytes(file)).stream().map(mapper).toList();
  }
}
