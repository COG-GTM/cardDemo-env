package com.carddemo.xferfee.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.TransferRequested;
import com.carddemo.xferfee.legacy.codec.CodecOptions;
import com.carddemo.xferfee.legacy.codec.CopybookCodec;
import com.carddemo.xferfee.legacy.codec.DecodedRecord;
import com.carddemo.xferfee.legacy.codec.SignStyle;
import com.carddemo.xferfee.legacy.egress.LegacyEgress;
import com.carddemo.xferfee.legacy.ingress.IngressBatch;
import com.carddemo.xferfee.legacy.ingress.LegacyFileIngress;
import com.carddemo.xferfee.legacy.ingress.LegacyInputFiles;
import com.carddemo.xferfee.legacy.io.FixedLengthFile;
import com.carddemo.xferfee.legacy.record.LegacyCopybooks;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtractSourceImageTest {

    private final CopybookCodec daily = CopybookCodec.of(LegacyCopybooks.DAILY_TRANSACTION, CodecOptions.GNUCOBOL_OUTPUT);
    private final CopybookCodec extract = CopybookCodec.of(LegacyCopybooks.TRANSFER_EXTRACT, CodecOptions.GNUCOBOL_OUTPUT);

    @Test
    void duplicateTranIdsKeepTheirOwnAmountSign(@TempDir Path dir) throws IOException {
        Path input = Fixtures.XFERFEE.resolve("default/input");
        for (Path file : Fixtures.files(input)) {
            Files.copy(file, dir.resolve(file.getFileName()));
        }
        Path dalytran = LegacyInputFiles.in(dir).dailyTransactions();
        List<DecodedRecord> records = daily.decodeAll(Files.readAllBytes(dalytran));
        DecodedRecord transfer = records.stream()
                .filter(r -> r.string("DALYTRAN-TYPE-CD").equals("08"))
                .findFirst().orElseThrow();
        SignStyle original = transfer.signStyle("DALYTRAN-AMT");
        SignStyle other = original == SignStyle.NATIVE ? SignStyle.OVERPUNCH : SignStyle.NATIVE;
        Map<String, SignStyle> styles = new HashMap<>(transfer.signStyles());
        styles.put("DALYTRAN-AMT", other);
        List<byte[]> bytes = new ArrayList<>(records.stream().map(daily::encode).toList());
        bytes.add(daily.encode(transfer.values(), styles, transfer.fillers()));
        try (OutputStream out = Files.newOutputStream(dalytran)) {
            FixedLengthFile.write(out, bytes);
        }

        IngressBatch batch = new LegacyFileIngress(CodecOptions.GNUCOBOL_OUTPUT).read(LegacyInputFiles.in(dir));
        String id = transfer.string("DALYTRAN-ID");
        List<TransferRequested> transfers = batch.transactions().stream()
                .filter(t -> t.tranId().equals(id))
                .map(ExtractSourceImageTest::request)
                .toList();
        assertThat(transfers).hasSize(2);

        List<byte[]> out = new LegacyEgress(CodecOptions.GNUCOBOL_OUTPUT).extractRecords(transfers, batch);
        assertThat(out).extracting(r -> extract.decode(r).signStyle("XFR-TRAN-AMT")).containsExactly(original, other);
    }

    private static TransferRequested request(DailyTransaction t) {
        return new TransferRequested(t.tranId(), LocalDate.parse(t.originTimestamp().substring(0, 10)),
                1L, 2L, "BOOK", t.amount(), t.cardNumber());
    }
}
