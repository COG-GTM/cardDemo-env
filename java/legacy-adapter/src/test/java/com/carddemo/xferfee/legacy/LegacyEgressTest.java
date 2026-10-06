package com.carddemo.xferfee.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRequested;
import com.carddemo.xferfee.legacy.codec.CodecOptions;
import com.carddemo.xferfee.legacy.codec.CopybookCodec;
import com.carddemo.xferfee.legacy.egress.LegacyEgress;
import com.carddemo.xferfee.legacy.ingress.IngressBatch;
import com.carddemo.xferfee.legacy.ingress.LegacyFileIngress;
import com.carddemo.xferfee.legacy.ingress.LegacyInputFiles;
import com.carddemo.xferfee.legacy.io.LineSequentialFile;
import com.carddemo.xferfee.legacy.record.LegacyCopybooks;
import com.carddemo.xferfee.legacy.record.LegacyRecords;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Given the contract-level results of a run, the egress writes exactly the bytes the GnuCOBOL
 * chain recorded: LOW-VALUE fillers, native signs on recomputed balances, source bytes on MOVEd
 * fields.
 */
class LegacyEgressTest {

    private final LegacyFileIngress ingress = new LegacyFileIngress(CodecOptions.GNUCOBOL_OUTPUT);
    private final LegacyEgress egress = new LegacyEgress(CodecOptions.GNUCOBOL_OUTPUT);

    static List<Path> cases() {
        return Fixtures.cases();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void writesRecordedDatasetsByteForByte(Path fixture) throws IOException {
        IngressBatch batch = ingress.read(LegacyInputFiles.in(fixture.resolve("input")));
        Path datasets = fixture.resolve("expected/datasets");

        List<TransferRequested> transfers = CopybookCodec.of(LegacyCopybooks.TRANSFER_EXTRACT, CodecOptions.GNUCOBOL_OUTPUT)
                .decodeAll(Fixtures.bytes(datasets.resolve(LegacyCopybooks.EXTRACT_DSN + ".G0001V00")))
                .stream().map(LegacyRecords::transferRequested).toList();
        assertThat(concat(egress.extractRecords(transfers, batch)))
                .isEqualTo(Fixtures.bytes(datasets.resolve(LegacyCopybooks.EXTRACT_DSN + ".G0001V00")));

        byte[] feesBytes = Fixtures.bytes(datasets.resolve(LegacyCopybooks.FEES_DSN + ".G0001V00"));
        List<TransferPosted> postings = CopybookCodec.of(LegacyCopybooks.TRANSFER_FEE, CodecOptions.GNUCOBOL_OUTPUT)
                .decodeAll(feesBytes).stream().map(LegacyRecords::transferPosted).toList();
        assertThat(concat(egress.feeRecords(postings))).isEqualTo(feesBytes);

        List<Account> after = post(batch.accounts().accounts(), postings, batch);
        assertThat(concat(egress.accountMasterRecords(batch.accounts(), after, postings)))
                .isEqualTo(Fixtures.bytes(datasets.resolve(LegacyCopybooks.ACCTDATA_XFER_DSN + ".G0001V00")));

        byte[] report = Fixtures.bytes(datasets.resolve(LegacyCopybooks.RECON_DSN + ".G0001V00"));
        assertThat(egress.reportBytes(LineSequentialFile.read(report, StandardCharsets.ISO_8859_1))).isEqualTo(report);
    }

    @Test
    void catalogsSuccessiveAcctdataXferGenerations(@TempDir Path dir) throws IOException {
        Path fixture = Fixtures.XFERFEE.resolve("default");
        IngressBatch batch = ingress.read(LegacyInputFiles.in(fixture.resolve("input")));
        List<Account> unchanged = batch.accounts().accounts();

        Path first = egress.writeAccountMasterGeneration(dir, batch.accounts(), unchanged, List.of());
        Path second = egress.writeAccountMasterGeneration(dir, batch.accounts(), unchanged, List.of());

        assertThat(first.getFileName()).hasToString("AWS.M2.CARDDEMO.ACCTDATA.XFER.G0001V00");
        assertThat(second.getFileName()).hasToString("AWS.M2.CARDDEMO.ACCTDATA.XFER.G0002V00");
        // nothing posted: every record keeps its input bytes apart from LOW-VALUE filler
        byte[] written = Files.readAllBytes(first);
        assertThat(written).hasSize(batch.accounts().size() * 300);
        assertThat(Files.list(dir).filter(p -> p.getFileName().toString().endsWith(".tmp"))).isEmpty();
    }

    @Test
    void failedWriteCatalogsNothing(@TempDir Path dir) {
        IngressBatch batch = readDefault();
        List<Account> tooMany = new java.util.ArrayList<>(batch.accounts().accounts());
        tooMany.add(tooMany.get(0));
        assertThatThrownBy(() -> egress.writeAccountMasterGeneration(dir, batch.accounts(), tooMany, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(dir.toFile().list()).isEmpty();
    }

    private IngressBatch readDefault() {
        try {
            return ingress.read(LegacyInputFiles.in(Fixtures.XFERFEE.resolve("default/input")));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** BR-11 arithmetic, applied to the entry XFERFEE posts to. */
    private static List<Account> post(List<Account> master, List<TransferPosted> postings, IngressBatch batch) {
        List<Account> accounts = new ArrayList<>(master);
        for (TransferPosted p : postings) {
            int src = batch.accounts().postingIndex(p.sourceAccountId());
            Account s = accounts.get(src);
            accounts.set(src, with(s, s.currentBalance().subtract(p.amount()).subtract(p.feeAmount()),
                    s.currentCycleCredit(), s.currentCycleDebit().add(p.amount()).add(p.feeAmount())));
            int tgt = batch.accounts().postingIndex(p.targetAccountId());
            Account t = accounts.get(tgt);
            accounts.set(tgt, with(t, t.currentBalance().add(p.amount()),
                    t.currentCycleCredit().add(p.amount()), t.currentCycleDebit()));
        }
        return accounts;
    }

    private static Account with(Account a, java.math.BigDecimal balance, java.math.BigDecimal credit,
            java.math.BigDecimal debit) {
        return new Account(a.accountId(), a.activeStatus(), balance, a.creditLimit(), a.cashCreditLimit(),
                a.openDate(), a.expirationDate(), a.reissueDate(), credit, debit, a.addressZip(), a.groupId());
    }

    private static byte[] concat(List<byte[]> records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        records.forEach(out::writeBytes);
        return out.toByteArray();
    }
}
