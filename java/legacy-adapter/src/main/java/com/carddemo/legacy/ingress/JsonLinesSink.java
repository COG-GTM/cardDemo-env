package com.carddemo.legacy.ingress;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * File-backed publisher and loader writing JSON Lines: {@code card.transactions.jsonl},
 * {@code accounts.jsonl} and {@code card-xref.jsonl}. Used by the CLI and for local inspection.
 */
public final class JsonLinesSink implements TransactionPublisher, ReferenceDataLoader, Closeable {
  public static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
          .disable(SerializationFeature.INDENT_OUTPUT);

  private final Path directory;
  private final BufferedWriter transactions;

  public JsonLinesSink(Path directory) throws IOException {
    this.directory = Files.createDirectories(directory);
    this.transactions =
        Files.newBufferedWriter(directory.resolve(TOPIC + ".jsonl"), StandardCharsets.UTF_8);
  }

  @Override
  public void publish(DailyTransaction transaction) {
    writeLine(transactions, transaction);
  }

  @Override
  public void loadAccounts(List<Account> accounts) {
    writeAll("accounts.jsonl", accounts);
  }

  @Override
  public void loadCardXrefs(List<CardXref> xrefs) {
    writeAll("card-xref.jsonl", xrefs);
  }

  private void writeAll(String name, List<?> rows) {
    try (BufferedWriter out = Files.newBufferedWriter(directory.resolve(name), StandardCharsets.UTF_8)) {
      for (Object row : rows) {
        writeLine(out, row);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeLine(BufferedWriter out, Object value) {
    try {
      out.write(JSON.writeValueAsString(value));
      out.newLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() throws IOException {
    transactions.close();
  }
}
