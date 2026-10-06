package com.carddemo.xferfee.legacy.egress;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRequested;
import com.carddemo.xferfee.legacy.codec.CodecOptions;
import com.carddemo.xferfee.legacy.codec.CopybookCodec;
import com.carddemo.xferfee.legacy.codec.DecodedRecord;
import com.carddemo.xferfee.legacy.codec.SignStyle;
import com.carddemo.xferfee.legacy.ingress.AccountMasterSnapshot;
import com.carddemo.xferfee.legacy.ingress.IngressBatch;
import com.carddemo.xferfee.legacy.io.FixedLengthFile;
import com.carddemo.xferfee.legacy.io.GenerationDataGroup;
import com.carddemo.xferfee.legacy.io.LineSequentialFile;
import com.carddemo.xferfee.legacy.record.LegacyCopybooks;
import com.carddemo.xferfee.legacy.record.LegacyRecords;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes chain datasets the way the GnuCOBOL programs do, so downstream mainframe consumers see
 * identical bytes: fresh records have LOW-VALUE fillers; fields the COBOL code computed carry the
 * native sign; fields it only MOVEd between identical pictures keep their source bytes.
 */
public final class LegacyEgress {

    private static final String BAL = "ACCT-CURR-BAL";
    private static final String CYC_CREDIT = "ACCT-CURR-CYC-CREDIT";
    private static final String CYC_DEBIT = "ACCT-CURR-CYC-DEBIT";

    private final CodecOptions options;
    private final CopybookCodec extract;
    private final CopybookCodec fees;
    private final CopybookCodec accounts;

    public LegacyEgress(CodecOptions options) {
        this.options = options;
        this.extract = CopybookCodec.of(LegacyCopybooks.TRANSFER_EXTRACT, options);
        this.fees = CopybookCodec.of(LegacyCopybooks.TRANSFER_FEE, options);
        this.accounts = CopybookCodec.of(LegacyCopybooks.ACCOUNT, options);
    }

    /**
     * XFER.EXTRACT (CVXFR01Y). CBXFR01C MOVEs TRAN-AMT to XFR-TRAN-AMT (same picture), so the
     * amount keeps the DALYTRAN sign representation.
     */
    public List<byte[]> extractRecords(List<TransferRequested> transfers, IngressBatch source) {
        List<byte[]> records = new ArrayList<>(transfers.size());
        for (TransferRequested transfer : transfers) {
            Map<String, SignStyle> styles = new HashMap<>();
            source.transactionImage(transfer.tranId())
                    .map(image -> image.signStyle("DALYTRAN-AMT"))
                    .ifPresent(style -> styles.put("XFR-TRAN-AMT", style));
            records.add(extract.encode(LegacyRecords.transferExtractFields(transfer), styles, Map.of()));
        }
        return records;
    }

    /** XFER.FEES (CVXFR02Y). */
    public List<byte[]> feeRecords(List<TransferPosted> postings) {
        return postings.stream().map(p -> fees.encode(LegacyRecords.transferFeeFields(p))).toList();
    }

    /**
     * ACCTDATA.XFER (CVACT01Y): one record per master entry, in master order (BR-16).
     *
     * @param updated positional account list (same size and order as {@code master})
     * @param postings postings applied, used to know which balances XFERFEE recomputed
     */
    public List<byte[]> accountMasterRecords(AccountMasterSnapshot master, List<Account> updated,
            List<TransferPosted> postings) {
        if (updated.size() != master.size()) {
            throw new IllegalArgumentException("updated master has " + updated.size()
                    + " accounts, input master has " + master.size());
        }
        Map<Integer, Set<String>> computed = new HashMap<>();
        for (TransferPosted posting : postings) {
            int source = master.postingIndex(posting.sourceAccountId());
            int target = master.postingIndex(posting.targetAccountId());
            if (source < 0 || target < 0) {
                throw new IllegalArgumentException("posting " + posting.tranId() + " references an account not on the master");
            }
            computed.computeIfAbsent(source, k -> new HashSet<>()).addAll(List.of(BAL, CYC_DEBIT));
            computed.computeIfAbsent(target, k -> new HashSet<>()).addAll(List.of(BAL, CYC_CREDIT));
        }
        List<byte[]> records = new ArrayList<>(master.size());
        for (int i = 0; i < master.size(); i++) {
            DecodedRecord original = master.images().get(i);
            Account account = updated.get(i);
            if (account.accountId() != master.accounts().get(i).accountId()) {
                throw new IllegalArgumentException("account at position " + i + " changed id");
            }
            Set<String> recomputed = computed.getOrDefault(i, Set.of());
            Map<String, Object> fields = LegacyRecords.accountFields(account);
            Map<String, SignStyle> styles = new LinkedHashMap<>();
            original.signStyles().forEach((field, style) -> {
                boolean changed = original.decimal(field).compareTo(new java.math.BigDecimal(fields.get(field).toString())) != 0;
                if (!recomputed.contains(field) && !changed) {
                    styles.put(field, style);
                }
            });
            records.add(accounts.encode(fields, styles, Map.of()));
        }
        return records;
    }

    /** XFER.RECON.RPT: line sequential, trailing spaces trimmed. */
    public byte[] reportBytes(List<String> lines) {
        return LineSequentialFile.write(lines, options.charset());
    }

    /** Catalogs a new (+1) generation of a fixed-length dataset. */
    public Path writeGeneration(Path datasetsDirectory, String baseDsn, List<byte[]> records) {
        return new GenerationDataGroup(datasetsDirectory, baseDsn).writeNext(out -> FixedLengthFile.write(out, records));
    }

    /** Writes the ACCTDATA.XFER (+1) generation for downstream mainframe consumers. */
    public Path writeAccountMasterGeneration(Path datasetsDirectory, AccountMasterSnapshot master,
            List<Account> updated, List<TransferPosted> postings) {
        return writeGeneration(datasetsDirectory, LegacyCopybooks.ACCTDATA_XFER_DSN,
                accountMasterRecords(master, updated, postings));
    }
}
