package com.carddemo.xferfee.legacy.ingress;

import com.carddemo.xferfee.legacy.codec.DecodedRecord;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import java.util.List;
import java.util.Optional;

/** Everything read from one run's three input datasets, in file order. */
public record IngressBatch(
        List<DailyTransaction> transactions,
        List<DecodedRecord> transactionImages,
        List<CardXref> xrefs,
        AccountMasterSnapshot accounts) {

    public IngressBatch {
        transactions = List.copyOf(transactions);
        transactionImages = List.copyOf(transactionImages);
        xrefs = List.copyOf(xrefs);
        if (transactions.size() != transactionImages.size()) {
            throw new IllegalArgumentException("transactions and images differ in size");
        }
    }

    /** Original DALYTRAN image of the first transaction with this id. */
    public Optional<DecodedRecord> transactionImage(String tranId) {
        for (int i = 0; i < transactions.size(); i++) {
            if (transactions.get(i).tranId().equals(tranId)) {
                return Optional.of(transactionImages.get(i));
            }
        }
        return Optional.empty();
    }
}
