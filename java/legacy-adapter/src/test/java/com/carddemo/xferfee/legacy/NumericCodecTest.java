package com.carddemo.xferfee.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.xferfee.legacy.codec.CopybookDataException;
import com.carddemo.xferfee.legacy.codec.FieldKind;
import com.carddemo.xferfee.legacy.codec.FieldSpec;
import com.carddemo.xferfee.legacy.codec.PackedDecimal;
import com.carddemo.xferfee.legacy.codec.SignStyle;
import com.carddemo.xferfee.legacy.codec.ZonedDecimal;
import java.math.BigDecimal;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NumericCodecTest {

    private static final FieldSpec S9_9_V99 = new FieldSpec("AMT", 0, 11, FieldKind.ZONED, 11, 2, true);
    private static final FieldSpec NINE_4 = new FieldSpec("CNT", 0, 4, FieldKind.ZONED, 4, 0, false);
    private static final FieldSpec P_S9_9_V99 = new FieldSpec("PAMT", 0, 6, FieldKind.PACKED, 11, 2, true);
    private static final FieldSpec P_S9_V9_6 = new FieldSpec("PCT", 0, 4, FieldKind.PACKED, 7, 6, true);

    @ParameterizedTest
    @CsvSource({
        "0000010000{, 1000.00, OVERPUNCH",
        "0000000012E, 1.25, OVERPUNCH",
        "0000000012I, 1.29, OVERPUNCH",
        "0000000012}, -1.20, OVERPUNCH",
        "0000000012N, -1.25, OVERPUNCH",
        "0000000012R, -1.29, OVERPUNCH",
        "00000100000, 1000.00, NATIVE",
        "0000000020q, -2.01, NATIVE",
        "0000000020y, -2.09, NATIVE",
        "0000000000p, 0.00, NATIVE"
    })
    void zonedOverpunchAndNativeSigns(String text, BigDecimal value, SignStyle style) {
        ZonedDecimal.Decoded decoded = ZonedDecimal.decode(text, S9_9_V99);
        assertThat(decoded.value()).isEqualByComparingTo(value);
        assertThat(decoded.value().scale()).isEqualTo(2);
        assertThat(decoded.style()).isEqualTo(style);
        assertThat(ZonedDecimal.encode(decoded.value(), S9_9_V99, style)).isEqualTo(text.equals("0000000000p") ? "00000000000" : text);
    }

    @Test
    void unsignedZoned() {
        assertThat(ZonedDecimal.decode("0042", NINE_4).value()).isEqualByComparingTo("42");
        assertThat(ZonedDecimal.decode("0042", NINE_4).style()).isEqualTo(SignStyle.UNSIGNED);
        assertThat(ZonedDecimal.encode(new BigDecimal("42"), NINE_4, SignStyle.UNSIGNED)).isEqualTo("0042");
        assertThatThrownBy(() -> ZonedDecimal.encode(new BigDecimal("-1"), NINE_4, SignStyle.UNSIGNED))
                .isInstanceOf(CopybookDataException.class);
        assertThatThrownBy(() -> ZonedDecimal.decode("004K", NINE_4)).isInstanceOf(CopybookDataException.class);
    }

    @Test
    void zonedRejectsOverflowExtraScaleAndGarbage() {
        assertThatThrownBy(() -> ZonedDecimal.encode(new BigDecimal("1000000000.00"), S9_9_V99, SignStyle.OVERPUNCH))
                .hasMessageContaining("overflows");
        assertThatThrownBy(() -> ZonedDecimal.encode(new BigDecimal("1.005"), S9_9_V99, SignStyle.OVERPUNCH))
                .hasMessageContaining("decimal places");
        assertThatThrownBy(() -> ZonedDecimal.decode("00000 0012{", S9_9_V99)).isInstanceOf(CopybookDataException.class);
        assertThatThrownBy(() -> ZonedDecimal.decode("0000000012#", S9_9_V99)).isInstanceOf(CopybookDataException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "00000001000C, 10.00, PACKED_C",
        "00000001000D, -10.00, PACKED_C",
        "00000001000F, 10.00, PACKED_F",
        "99999999999C, 999999999.99, PACKED_C",
        "00000000000C, 0.00, PACKED_C"
    })
    void packedAmounts(String hex, BigDecimal value, SignStyle style) {
        byte[] bytes = HexFormat.of().parseHex(hex);
        PackedDecimal.Decoded decoded = PackedDecimal.decode(bytes, 0, P_S9_9_V99);
        assertThat(decoded.value()).isEqualByComparingTo(value);
        assertThat(decoded.style()).isEqualTo(style);
        byte[] encoded = new byte[6];
        PackedDecimal.encode(decoded.value(), P_S9_9_V99, style, encoded, 0);
        assertThat(HexFormat.of().withUpperCase().formatHex(encoded)).isEqualTo(hex);
    }

    @Test
    void packedRateWithSixImpliedDecimals() {
        byte[] encoded = new byte[4];
        PackedDecimal.encode(new BigDecimal("0.015000"), P_S9_V9_6, SignStyle.PACKED_C, encoded, 0);
        assertThat(HexFormat.of().withUpperCase().formatHex(encoded)).isEqualTo("0015000C");
        assertThat(PackedDecimal.decode(encoded, 0, P_S9_V9_6).value()).isEqualTo(new BigDecimal("0.015000"));
    }

    @Test
    void packedRejectsBadNibbles() {
        assertThatThrownBy(() -> PackedDecimal.decode(HexFormat.of().parseHex("0000000A000C"), 0, P_S9_9_V99))
                .isInstanceOf(CopybookDataException.class);
        assertThatThrownBy(() -> PackedDecimal.decode(HexFormat.of().parseHex("000000010001"), 0, P_S9_9_V99))
                .isInstanceOf(CopybookDataException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "00000000120A, 1.20, PACKED_A",
        "00000000120E, 1.20, PACKED_E",
        "00000000120B, -1.20, PACKED_B",
        "00000000120C, 1.20, PACKED_C",
        "00000000120D, -1.20, PACKED_C",
        "00000000120F, 1.20, PACKED_F"
    })
    void everyPackedSignNibbleRoundTrips(String hex, BigDecimal value, SignStyle style) {
        byte[] bytes = HexFormat.of().parseHex(hex);
        PackedDecimal.Decoded decoded = PackedDecimal.decode(bytes, 0, P_S9_9_V99);
        assertThat(decoded.value()).isEqualByComparingTo(value);
        assertThat(decoded.style()).isEqualTo(style);
        byte[] out = new byte[6];
        PackedDecimal.encode(decoded.value(), P_S9_9_V99, decoded.style(), decoded.negativeZero(), out, 0);
        assertThat(HexFormat.of().withUpperCase().formatHex(out)).isEqualTo(hex);
    }

    @Test
    void alternateSignStylesFollowTheValueSignWhenItChanges() {
        byte[] out = new byte[6];
        PackedDecimal.encode(new BigDecimal("-1.20"), P_S9_9_V99, SignStyle.PACKED_A, out, 0);
        assertThat(HexFormat.of().withUpperCase().formatHex(out)).isEqualTo("00000000120D");
        PackedDecimal.encode(new BigDecimal("1.20"), P_S9_9_V99, SignStyle.PACKED_B, out, 0);
        assertThat(HexFormat.of().withUpperCase().formatHex(out)).isEqualTo("00000000120C");
    }

    @ParameterizedTest
    @CsvSource({"0000000000}, OVERPUNCH", "0000000000p, NATIVE"})
    void zonedNegativeZeroRoundTrips(String text, SignStyle style) {
        ZonedDecimal.Decoded decoded = ZonedDecimal.decode(text, S9_9_V99);
        assertThat(decoded.value().signum()).isZero();
        assertThat(decoded.negativeZero()).isTrue();
        assertThat(ZonedDecimal.encode(decoded.value(), S9_9_V99, style, decoded.negativeZero())).isEqualTo(text);
        assertThat(ZonedDecimal.decode(text.substring(0, 10) + (style == SignStyle.NATIVE ? "0" : "{"), S9_9_V99)
                .negativeZero()).isFalse();
    }

    @Test
    void negativeZeroFlagIsDroppedOnceTheValueChanges() {
        assertThat(ZonedDecimal.encode(new BigDecimal("1.00"), S9_9_V99, SignStyle.OVERPUNCH, true)).isEqualTo("0000000010{");
        byte[] out = new byte[6];
        PackedDecimal.encode(new BigDecimal("1.00"), P_S9_9_V99, SignStyle.PACKED_C, true, out, 0);
        assertThat(HexFormat.of().withUpperCase().formatHex(out)).isEqualTo("00000000100C");
    }

    @ParameterizedTest
    @CsvSource({"00000000000D", "00000000000B"})
    void packedNegativeZeroRoundTrips(String hex) {
        PackedDecimal.Decoded decoded = PackedDecimal.decode(HexFormat.of().parseHex(hex), 0, P_S9_9_V99);
        assertThat(decoded.value().signum()).isZero();
        assertThat(decoded.negativeZero()).isTrue();
        byte[] out = new byte[6];
        PackedDecimal.encode(decoded.value(), P_S9_9_V99, decoded.style(), decoded.negativeZero(), out, 0);
        assertThat(HexFormat.of().withUpperCase().formatHex(out)).isEqualTo(hex);
    }
}
