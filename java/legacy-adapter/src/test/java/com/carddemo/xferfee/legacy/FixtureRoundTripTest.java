package com.carddemo.xferfee.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.legacy.codec.CodecOptions;
import com.carddemo.xferfee.legacy.codec.CopybookCodec;
import com.carddemo.xferfee.legacy.codec.DecodedRecord;
import com.carddemo.xferfee.legacy.io.FixedLengthFile;
import com.carddemo.xferfee.legacy.io.LineSequentialFile;
import com.carddemo.xferfee.legacy.record.LegacyCopybooks;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every input/*.PS and expected/datasets/* file of every xferfee fixture decodes and re-encodes byte for byte. */
class FixtureRoundTripTest {

    private static final Map<String, String> COPYBOOK_BY_NAME = Map.of(
            "DALYTRAN.PS", LegacyCopybooks.DAILY_TRANSACTION,
            "CARDXREF.PS", LegacyCopybooks.CARD_XREF,
            "ACCTDATA.PS", LegacyCopybooks.ACCOUNT,
            LegacyCopybooks.EXTRACT_DSN, LegacyCopybooks.TRANSFER_EXTRACT,
            LegacyCopybooks.ACCTDATA_XFER_DSN, LegacyCopybooks.ACCOUNT,
            LegacyCopybooks.FEES_DSN, LegacyCopybooks.TRANSFER_FEE);

    static Stream<Arguments> fixtureFiles() {
        List<Arguments> files = new ArrayList<>();
        for (Path fixture : Fixtures.cases()) {
            for (Path file : Fixtures.files(fixture.resolve("input"))) {
                files.add(Arguments.of(fixture.getFileName() + "/input/" + file.getFileName(), file));
            }
            for (Path file : Fixtures.files(fixture.resolve("expected/datasets"))) {
                files.add(Arguments.of(fixture.getFileName() + "/expected/datasets/" + file.getFileName(), file));
            }
        }
        return files.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureFiles")
    void roundTripsByteForByte(String label, Path file) throws IOException {
        byte[] original = Fixtures.bytes(file);
        String name = file.getFileName().toString().replaceFirst("\\.G\\d{4}V00$", "");
        if (name.equals(LegacyCopybooks.RECON_DSN)) {
            List<String> lines = LineSequentialFile.read(original, StandardCharsets.ISO_8859_1);
            assertThat(LineSequentialFile.write(lines, StandardCharsets.ISO_8859_1)).isEqualTo(original);
            return;
        }
        String copybook = COPYBOOK_BY_NAME.get(name);
        assertThat(copybook).as("copybook for %s", name).isNotNull();
        CopybookCodec codec = CopybookCodec.of(copybook, CodecOptions.GNUCOBOL_OUTPUT);
        List<byte[]> records = FixedLengthFile.split(original, codec.layout().recordLength(), label);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] record : records) {
            DecodedRecord decoded = codec.decode(record);
            // re-encode from values + sign styles + filler bytes only, not from the raw record
            out.write(codec.encode(Map.copyOf(decoded.values()), decoded.signStyles(), decoded.fillers(),
                    decoded.negativeZeros()));
        }
        assertThat(out.toByteArray()).isEqualTo(original);
    }

    @Test
    void coversEveryFixtureFile() {
        assertThat(fixtureFiles().count()).isEqualTo(7 * (3 + 4));
    }

    static Stream<Arguments> ebcdicDatasets() {
        return Stream.of(
                Arguments.of("AWS.M2.CARDDEMO.ACCTDATA.PS", LegacyCopybooks.ACCOUNT),
                Arguments.of("AWS.M2.CARDDEMO.CARDXREF.PS", LegacyCopybooks.CARD_XREF),
                Arguments.of("AWS.M2.CARDDEMO.DALYTRAN.PS", LegacyCopybooks.DAILY_TRANSACTION));
    }

    /** The original CardDemo z/OS datasets (EBCDIC, code page 037) use the same layouts. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ebcdicDatasets")
    void roundTripsEbcdicMainframeDatasets(String dsn, String copybook) {
        byte[] original = Fixtures.bytes(Fixtures.ROOT.resolve("carddemo/ebcdic").resolve(dsn));
        CopybookCodec codec = CopybookCodec.of(copybook, CodecOptions.EBCDIC_037);
        List<DecodedRecord> records = codec.decodeAll(original);
        assertThat(records).isNotEmpty();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        records.forEach(record -> out.writeBytes(codec.encode(record)));
        assertThat(out.toByteArray()).isEqualTo(original);
    }
}
