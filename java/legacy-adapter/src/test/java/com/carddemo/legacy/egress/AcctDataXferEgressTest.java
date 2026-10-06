package com.carddemo.legacy.egress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.legacy.Estate;
import com.carddemo.legacy.codec.CopybookCodec;
import com.carddemo.legacy.codec.CopybookRecord;
import com.carddemo.legacy.ingress.LegacyFileIngress;
import com.carddemo.legacy.codec.SignEncoding;
import com.carddemo.legacy.copybook.CopybookParser;
import com.carddemo.legacy.ingress.Account;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

class AcctDataXferEgressTest {
  private final CopybookParser copybooks = Estate.copybooks();

  private List<Account> accounts(Path file) throws Exception {
    CopybookCodec codec = new CopybookCodec(copybooks.layout("CVACT01Y"), SignEncoding.OVERPUNCH);
    return codec.decodeAll(Files.readAllBytes(file)).stream().map(Account::from).toList();
  }

  @Test
  void writesFreshAccountGenerationInTheEstateLayout(@TempDir Path datasets) throws Exception {
    Path expected = Estate.fixture("default").resolve("expected/datasets/AWS.M2.CARDDEMO.ACCTDATA.XFER.G0001V00");
    List<Account> accounts = accounts(expected);

    Path written = new AcctDataXferEgress(copybooks, SignEncoding.GNUCOBOL).write(datasets, accounts);

    assertEquals("AWS.M2.CARDDEMO.ACCTDATA.XFER.G0001V00", written.getFileName().toString());
    assertEquals("{\"current\":1}\n", Files.readString(datasets.resolve("AWS.M2.CARDDEMO.ACCTDATA.XFER.gdg")));
    assertEquals(accounts, accounts(written));
    byte[] bytes = Files.readAllBytes(written);
    assertEquals(Files.size(expected), bytes.length);
    for (int r = 0; r < bytes.length; r += 300) {
      for (int i = 122; i < 300; i++) {
        assertEquals(' ', bytes[r + i], "fresh FILLER is blank");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("com.carddemo.legacy.Estate#cases")
  void overlayOnIngressImagesMatchesTheCobolGeneration(String caseName, @TempDir Path datasets)
      throws Exception {
    Path fixture = Estate.fixture(caseName);
    Path expected = fixture.resolve("expected/datasets/AWS.M2.CARDDEMO.ACCTDATA.XFER.G0001V00");
    List<CopybookRecord> images =
        new LegacyFileIngress(copybooks, t -> {}, null).accountImages(fixture.resolve("input"));

    Path written =
        new AcctDataXferEgress(copybooks, SignEncoding.GNUCOBOL).write(datasets, accounts(expected), images);

    assertEquals(accounts(expected), accounts(written));
    byte[] out = Files.readAllBytes(written);
    for (int r = 0; r < images.size(); r++) {
      CopybookRecord image = images.get(r);
      assertEquals(accounts(expected).get(r).acctId(), image.longValue("ACCT-ID"), "record order");
      byte[] source = new CopybookCodec(copybooks.layout("CVACT01Y"), SignEncoding.OVERPUNCH).encode(image);
      assertArrayEquals(
          java.util.Arrays.copyOfRange(source, 122, 300),
          java.util.Arrays.copyOfRange(out, r * 300 + 122, r * 300 + 300),
          "source FILLER carried over");
    }
  }

  @Test
  void advancesGenerationsAndScratchesBeyondLimit(@TempDir Path datasets) throws Exception {
    List<Account> accounts = accounts(Estate.fixture("default").resolve("input/ACCTDATA.PS"));
    AcctDataXferEgress egress = new AcctDataXferEgress(copybooks, SignEncoding.OVERPUNCH);
    Path last = null;
    for (int i = 0; i < 7; i++) {
      last = egress.write(datasets, accounts);
    }
    assertEquals("AWS.M2.CARDDEMO.ACCTDATA.XFER.G0007V00", last.getFileName().toString());
    GenerationDataGroup gdg = new GenerationDataGroup(datasets, AcctDataXferEgress.DSN, 5);
    assertEquals(7, gdg.current());
    assertFalse(Files.exists(gdg.generation(1)));
    assertFalse(Files.exists(gdg.generation(2)));
    for (int g = 3; g <= 7; g++) {
      assertTrue(Files.exists(gdg.generation(g)), "G" + g);
    }
    assertArrayEquals(Files.readAllBytes(Estate.fixture("default").resolve("input/ACCTDATA.PS")), Files.readAllBytes(last));
    try (var files = Files.list(datasets)) {
      assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp")));
    }
  }
}
