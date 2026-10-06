package com.carddemo.legacy.cli;

import com.carddemo.legacy.candidate.CandidateEncoder;
import com.carddemo.legacy.codec.CopybookCodec;
import com.carddemo.legacy.codec.SignEncoding;
import com.carddemo.legacy.copybook.CopybookParser;
import com.carddemo.legacy.egress.AcctDataXferEgress;
import com.carddemo.legacy.ingress.Account;
import com.carddemo.legacy.ingress.JsonLinesSink;
import com.carddemo.legacy.ingress.LegacyFileIngress;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Command-line entry point for the legacy adapter. */
public final class LegacyAdapterCli {
  private static final String USAGE =
      """
      usage: legacy-adapter <command> [options]   (common: --copybooks DIR, default ./copybook)

        encode-candidate --case CASE_JSON --decoded DIR --out DIR [--sign overpunch|gnucobol]
            Encode decoded records into a parity candidate directory (--codec=java).
        roundtrip --copybook NAME --in FILE
            Decode and re-encode a dataset; exit 0 only if the bytes are identical.
        decode --copybook NAME --in FILE [--out FILE]
            Decode a dataset to JSON Lines (decimals as plain strings).
        ingest --input DIR --out DIR
            Read DALYTRAN.PS/ACCTDATA.PS/CARDXREF.PS and write card.transactions/accounts/xref JSONL.
        egress --accounts FILE --datasets DIR [--sign overpunch|gnucobol]
            Write the accounts in FILE (CVACT01Y) as a new ACCTDATA.XFER generation.
      """;

  private LegacyAdapterCli() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
      out.print(USAGE);
      return args.length == 0 ? 2 : 0;
    }
    try {
      Map<String, String> opts = options(Arrays.copyOfRange(args, 1, args.length));
      CopybookParser copybooks = new CopybookParser(Path.of(opts.getOrDefault("copybooks", "copybook")));
      return switch (args[0]) {
        case "encode-candidate" -> encodeCandidate(copybooks, opts, out);
        case "roundtrip" -> roundTrip(copybooks, opts, out, err);
        case "decode" -> decode(copybooks, opts, out);
        case "ingest" -> ingest(copybooks, opts, out);
        case "egress" -> egress(copybooks, opts, out);
        default -> {
          err.println("unknown command: " + args[0]);
          err.print(USAGE);
          yield 2;
        }
      };
    } catch (IllegalArgumentException e) {
      err.println("error: " + e.getMessage());
      return 2;
    } catch (Exception e) {
      err.println("error: " + e);
      return 1;
    }
  }

  private static int encodeCandidate(CopybookParser copybooks, Map<String, String> o, PrintStream out)
      throws Exception {
    List<Path> written =
        new CandidateEncoder(copybooks, sign(o, SignEncoding.OVERPUNCH))
            .encode(path(o, "case"), path(o, "decoded"), path(o, "out"));
    written.forEach(p -> out.println("wrote " + p));
    return 0;
  }

  private static int roundTrip(
      CopybookParser copybooks, Map<String, String> o, PrintStream out, PrintStream err)
      throws Exception {
    Path file = path(o, "in");
    byte[] original = Files.readAllBytes(file);
    CopybookCodec codec =
        new CopybookCodec(copybooks.layout(require(o, "copybook")), sign(o, SignEncoding.OVERPUNCH));
    byte[] encoded = codec.encodeAll(codec.decodeAll(original));
    if (Arrays.equals(original, encoded)) {
      out.printf(
          "OK %s: %d records, %d bytes, zoned signs %s%n",
          file, original.length / codec.layout().recordLength(), original.length, codec.signEncodingsUsed(original));
      return 0;
    }
    err.printf("MISMATCH %s at byte %d%n", file, Arrays.mismatch(original, encoded));
    return 1;
  }

  private static int decode(CopybookParser copybooks, Map<String, String> o, PrintStream out)
      throws Exception {
    CopybookCodec codec = new CopybookCodec(copybooks.layout(require(o, "copybook")), SignEncoding.OVERPUNCH);
    StringBuilder lines = new StringBuilder();
    for (var record : codec.decodeAll(Files.readAllBytes(path(o, "in")))) {
      Map<String, Object> row = new java.util.LinkedHashMap<>();
      record.values().forEach((k, v) -> row.put(k, v instanceof java.math.BigDecimal d ? d.toPlainString() : v));
      lines.append(JsonLinesSink.JSON.writeValueAsString(row)).append('\n');
    }
    if (o.containsKey("out")) {
      Files.writeString(path(o, "out"), lines);
    } else {
      out.print(lines);
    }
    return 0;
  }

  private static int ingest(CopybookParser copybooks, Map<String, String> o, PrintStream out)
      throws Exception {
    try (JsonLinesSink sink = new JsonLinesSink(path(o, "out"))) {
      LegacyFileIngress.Summary summary = new LegacyFileIngress(copybooks, sink, sink).ingest(path(o, "input"));
      out.printf(
          "accounts=%d card_xrefs=%d %s=%d%n",
          summary.accounts(), summary.cardXrefs(), "card.transactions", summary.transactions());
    }
    return 0;
  }

  private static int egress(CopybookParser copybooks, Map<String, String> o, PrintStream out)
      throws Exception {
    CopybookCodec reader = new CopybookCodec(copybooks.layout(Account.COPYBOOK), SignEncoding.OVERPUNCH);
    List<Account> accounts =
        reader.decodeAll(Files.readAllBytes(path(o, "accounts"))).stream().map(Account::from).toList();
    Path written =
        new AcctDataXferEgress(copybooks, sign(o, SignEncoding.OVERPUNCH)).write(path(o, "datasets"), accounts);
    out.println("wrote " + written + " (" + accounts.size() + " accounts)");
    return 0;
  }

  private static Map<String, String> options(String[] args) {
    Map<String, String> opts = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (!arg.startsWith("--")) {
        throw new IllegalArgumentException("unexpected argument: " + arg);
      }
      int eq = arg.indexOf('=');
      if (eq > 0) {
        opts.put(arg.substring(2, eq), arg.substring(eq + 1));
      } else if (i + 1 < args.length) {
        opts.put(arg.substring(2), args[++i]);
      } else {
        throw new IllegalArgumentException("missing value for " + arg);
      }
    }
    return opts;
  }

  private static String require(Map<String, String> o, String name) {
    String value = o.get(name);
    if (value == null) {
      throw new IllegalArgumentException("--" + name + " is required");
    }
    return value;
  }

  private static Path path(Map<String, String> o, String name) {
    return Path.of(require(o, name));
  }

  private static SignEncoding sign(Map<String, String> o, SignEncoding fallback) {
    String value = o.get("sign");
    return value == null ? fallback : SignEncoding.valueOf(value.toUpperCase(Locale.ROOT));
  }
}
