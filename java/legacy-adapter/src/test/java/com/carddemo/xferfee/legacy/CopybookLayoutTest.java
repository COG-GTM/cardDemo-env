package com.carddemo.xferfee.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.xferfee.legacy.codec.CopybookLayout;
import com.carddemo.xferfee.legacy.codec.CopybookParser;
import com.carddemo.xferfee.legacy.codec.FieldKind;
import com.carddemo.xferfee.legacy.codec.FieldSpec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class CopybookLayoutTest {

    @ParameterizedTest
    @CsvSource({"CVTRA06Y,350", "CVACT03Y,50", "CVACT01Y,300", "CVXFR01Y,120", "CVXFR02Y,100"})
    void recordLengthsMatchTheDcbLrecl(String copybook, int lrecl) {
        assertThat(CopybookLayout.load(copybook).recordLength()).isEqualTo(lrecl);
    }

    @Test
    void parsesZonedPackedAndImpliedDecimals() {
        CopybookLayout fees = CopybookLayout.load("CVXFR02Y");
        assertThat(fees.field("XFE-TRAN-AMT"))
                .isEqualTo(new FieldSpec("XFE-TRAN-AMT", 58, 6, FieldKind.PACKED, 11, 2, true));
        assertThat(fees.field("XFE-FEE-PCT"))
                .isEqualTo(new FieldSpec("XFE-FEE-PCT", 64, 4, FieldKind.PACKED, 7, 6, true));
        assertThat(fees.field("XFE-CAP-APPLIED").offset()).isEqualTo(74);

        CopybookLayout daily = CopybookLayout.load("CVTRA06Y");
        assertThat(daily.field("DALYTRAN-AMT"))
                .isEqualTo(new FieldSpec("DALYTRAN-AMT", 132, 11, FieldKind.ZONED, 11, 2, true));
        assertThat(daily.field("DALYTRAN-CAT-CD"))
                .isEqualTo(new FieldSpec("DALYTRAN-CAT-CD", 18, 4, FieldKind.ZONED, 4, 0, false));
        assertThat(daily.field("DALYTRAN-DESC").kind()).isEqualTo(FieldKind.ALPHANUMERIC);
        assertThat(daily.field("FILLER@330").length()).isEqualTo(20);
    }

    @Test
    void rejectsLayoutsItCannotRepresent() {
        String redefines = """
                       01  REC.
                           05  A   PIC X(4).
                           05  B   REDEFINES A PIC 9(4).
                """;
        assertThatThrownBy(() -> CopybookParser.parse("BAD", redefines))
                .hasMessageContaining("REDEFINES");
        String binary = """
                       01  REC.
                           05  A   PIC S9(4) COMP.
                """;
        assertThatThrownBy(() -> CopybookParser.parse("BAD", binary)).hasMessageContaining("COMP");
        String edited = """
                       01  REC.
                           05  A   PIC Z(4)9.99-.
                """;
        assertThatThrownBy(() -> CopybookParser.parse("BAD", edited)).hasMessageContaining("picture");
    }

    @Test
    void expandsElementaryOccursAndSkipsComments() {
        String source = """
                      * a comment line
                       01  REC.
                           05  CODES   PIC X(2) OCCURS 3 TIMES.
                           05  AMT     PIC S9(3)V9(2) COMP-3.
                           88  ZERO-AMT VALUE 0.
                """;
        CopybookLayout layout = CopybookParser.parse("OCC", source);
        assertThat(layout.fields()).extracting(FieldSpec::name)
                .containsExactly("CODES[1]", "CODES[2]", "CODES[3]", "AMT");
        assertThat(layout.recordLength()).isEqualTo(9);
    }
}
