package com.carddemo.legacy.copybook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.legacy.Estate;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class CopybookParserTest {
  private final CopybookParser parser = Estate.copybooks();

  @ParameterizedTest
  @CsvSource({"CVACT01Y,300", "CVACT03Y,50", "CVTRA06Y,350", "CVXFR01Y,120", "CVXFR02Y,100"})
  void recordLengthsMatchTheDatasets(String copybook, int length) {
    assertEquals(length, parser.layout(copybook).recordLength());
  }

  @Test
  void parsesZonedPackedAndAlphanumericItems() {
    CopybookLayout fees = parser.layout("cvxfr02y");
    assertEquals(
        new CopybookField("XFE-TRAN-AMT", 58, 6, FieldKind.PACKED, 11, 2, true),
        fees.requireField("XFE-TRAN-AMT"));
    assertEquals(
        new CopybookField("XFE-FEE-PCT", 64, 4, FieldKind.PACKED, 7, 6, true),
        fees.requireField("XFE-FEE-PCT"));
    CopybookLayout account = parser.layout("CVACT01Y");
    assertEquals(
        new CopybookField("ACCT-CURR-BAL", 12, 12, FieldKind.ZONED, 12, 2, true),
        account.requireField("ACCT-CURR-BAL"));
    assertEquals(
        new CopybookField("ACCT-ID", 0, 11, FieldKind.ZONED, 11, 0, false), account.requireField("ACCT-ID"));
    CopybookField filler = account.fields().get(account.fields().size() - 1);
    assertTrue(filler.isFiller());
    assertEquals(122, filler.offset());
    assertEquals(178, filler.length());
    assertFalse(account.dataFields().contains(filler));
  }

  @Test
  void ignoresCommentsSequenceAreaAndGroupItems() {
    CopybookLayout layout =
        CopybookParser.parse(
            "T",
            List.of(
                "      * comment line PIC X(99).",
                "       01  T-REC.                                                       SEQ00001",
                "           05  T-GROUP.",
                "               10  T-A       PIC X(03).",
                "               10  T-B       PIC S9(3)V9(2) USAGE IS COMP-3.",
                "           05  T-C           PIC 9(02) OCCURS 3 TIMES.",
                "           05  FILLER        PIC X(05)."));
    assertEquals(3 + 3 + 6 + 5, layout.recordLength());
    assertEquals(List.of("T-A", "T-B", "T-C[1]", "T-C[2]", "T-C[3]"),
        layout.dataFields().stream().map(CopybookField::name).toList());
    assertEquals(10, layout.requireField("T-C[3]").offset());
  }

  @Test
  void rejectsUnsupportedConstructs() {
    assertThrows(IllegalArgumentException.class,
        () -> CopybookParser.parse("T", List.of("       05  A REDEFINES B PIC X(2).")));
    assertThrows(IllegalArgumentException.class,
        () -> CopybookParser.parse("T", List.of("       05  A PIC S9(19).")));
    assertThrows(IllegalArgumentException.class, () -> parser.layout("NOPE"));
  }
}
