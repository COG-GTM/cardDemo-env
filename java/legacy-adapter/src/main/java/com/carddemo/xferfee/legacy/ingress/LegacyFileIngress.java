package com.carddemo.xferfee.legacy.ingress;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.legacy.codec.CodecOptions;
import com.carddemo.xferfee.legacy.codec.CopybookCodec;
import com.carddemo.xferfee.legacy.codec.DecodedRecord;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.legacy.record.LegacyCopybooks;
import com.carddemo.xferfee.legacy.record.LegacyRecords;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads DALYTRAN, CARDXREF and ACCTDATA as written by the mainframe and hands them to the Java
 * services: every daily transaction is published to {@code card.transactions} in file order (type
 * selection is the intake service's job, BR-01), accounts and xrefs are loaded into their stores.
 */
public final class LegacyFileIngress {

    public static final String CARD_TRANSACTIONS_TOPIC = "card.transactions";

    private final CopybookCodec transactions;
    private final CopybookCodec xrefs;
    private final CopybookCodec accounts;

    public LegacyFileIngress(CodecOptions options) {
        this.transactions = CopybookCodec.of(LegacyCopybooks.DAILY_TRANSACTION, options);
        this.xrefs = CopybookCodec.of(LegacyCopybooks.CARD_XREF, options);
        this.accounts = CopybookCodec.of(LegacyCopybooks.ACCOUNT, options);
    }

    public IngressBatch read(LegacyInputFiles files) throws IOException {
        List<DecodedRecord> transactionImages = transactions.decodeAll(Files.readAllBytes(files.dailyTransactions()));
        List<CardXref> xrefRecords = decode(xrefs, files.cardXref()).stream().map(LegacyRecords::cardXref).toList();
        AccountMasterSnapshot master = new AccountMasterSnapshot(decode(accounts, files.accountMaster()));
        return new IngressBatch(
                transactionImages.stream().map(LegacyRecords::dailyTransaction).toList(),
                transactionImages, xrefRecords, master);
    }

    /** Publishes every transaction, keyed by card number so per-card order is kept. */
    public int publish(IngressBatch batch, TransactionPublisher publisher) {
        int sequence = 0;
        for (DailyTransaction transaction : batch.transactions()) {
            sequence++;
            publisher.publish(CARD_TRANSACTIONS_TOPIC, transaction.cardNumber(),
                    new CardTransactionEvent(sequence, transaction));
        }
        return sequence;
    }

    public void load(IngressBatch batch, Consumer<Account> accountStore, Consumer<CardXref> xrefStore) {
        batch.accounts().accounts().forEach(accountStore);
        batch.xrefs().forEach(xrefStore);
    }

    private static List<DecodedRecord> decode(CopybookCodec codec, Path path) throws IOException {
        return codec.decodeAll(Files.readAllBytes(path));
    }
}
