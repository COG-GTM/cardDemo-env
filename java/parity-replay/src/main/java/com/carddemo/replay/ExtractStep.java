package com.carddemo.replay;

import com.carddemo.contracts.RejectReason;
import com.carddemo.contracts.TransferRejected;
import com.carddemo.observability.ChainStep;
import com.carddemo.observability.LegacyCounter;
import com.carddemo.observability.StepCounters;
import com.carddemo.observability.StepOutcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CBXFR01C: select type-08 transactions and resolve source account and book. */
final class ExtractStep {

    private static final int TABLE_LIMIT = 500;
    private static final ChainStep STEP = ChainStep.STEP010;

    record Result(StepOutcome outcome, List<Transfer> transfers) {
    }

    Result run(FixtureCase fixture) {
        List<String[]> xref = new ArrayList<>();
        for (byte[] r : fixture.cardXref().subList(0, Math.min(TABLE_LIMIT, fixture.cardXref().size()))) {
            xref.add(new String[] {FixedRecords.text(r, 0, 16), Long.toString(FixedRecords.unsigned(r, 25, 11))});
        }
        Map<Long, String> books = new LinkedHashMap<>();
        for (byte[] r : fixture.accounts().subList(0, Math.min(TABLE_LIMIT, fixture.accounts().size()))) {
            books.putIfAbsent(FixedRecords.unsigned(r, 0, 11), FixedRecords.text(r, 112, 10));
        }

        StepCounters counters = new StepCounters(STEP);
        List<String> messages = new ArrayList<>();
        List<TransferRejected> rejects = new ArrayList<>();
        List<Transfer> transfers = new ArrayList<>();
        for (byte[] tran : fixture.dailyTransactions()) {
            counters.increment(LegacyCounter.RECORDS_READ);
            if (!"08".equals(FixedRecords.text(tran, 16, 2))) {
                continue;
            }
            String tranId = FixedRecords.text(tran, 0, 16);
            String cardNum = FixedRecords.text(tran, 262, 16);
            Long srcAcct = xref.stream().filter(x -> x[0].equals(cardNum)).findFirst()
                    .map(x -> Long.parseLong(x[1])).orElse(null);
            if (srcAcct == null) {
                messages.add(STEP.program() + ": CARD NOT FOUND " + cardNum);
                counters.increment(LegacyCounter.UNMATCHED_CARDS);
                rejects.add(reject(tranId, cardNum, RejectReason.CARD_NOT_FOUND, "card " + cardNum.strip()));
                continue;
            }
            String book = books.get(srcAcct);
            if (book == null) {
                String acct = String.format("%011d", srcAcct);
                messages.add(STEP.program() + ": ACCOUNT NOT FOUND " + acct);
                counters.increment(LegacyCounter.UNMATCHED_CARDS);
                rejects.add(reject(tranId, cardNum, RejectReason.ACCOUNT_NOT_FOUND, "account " + acct));
                continue;
            }
            transfers.add(new Transfer(
                    tranId,
                    FixedRecords.text(tran, 278, 10),
                    srcAcct,
                    FixedRecords.unsigned(tran, 32 + 13, 11),
                    book,
                    FixedRecords.signed(tran, 132, 11, 2),
                    cardNum));
            counters.increment(LegacyCounter.TRANSFERS_SELECTED);
        }
        int rc = counters.get(LegacyCounter.UNMATCHED_CARDS).signum() > 0 ? 4 : 0;
        return new Result(new StepOutcome(STEP, rc, counters, true, messages, rejects, List.of()), transfers);
    }

    private static TransferRejected reject(String tranId, String cardNum, RejectReason reason, String detail) {
        return new TransferRejected(tranId.strip(), cardNum.strip(), STEP.name(), STEP.program(), reason, 4, detail);
    }
}
