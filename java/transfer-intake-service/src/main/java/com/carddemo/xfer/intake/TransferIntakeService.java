package com.carddemo.xfer.intake;

import com.carddemo.xfer.contracts.TransferRejected;
import com.carddemo.xfer.contracts.TransferRequested;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces CBXFR01C (XFRDAILY STEP010).
 *
 * <ul>
 *   <li>BR-01: only {@code TRAN-TYPE-CD = "08"} is selected; every record read is counted.</li>
 *   <li>BR-02: card to source account via the card cross-reference, first match wins.</li>
 *   <li>BR-03: source account to book via {@code ACCT-GROUP-ID}, first match wins.</li>
 *   <li>BR-04: target account = {@code TRAN-DESC(14:11)}, business date =
 *       {@code TRAN-ORIG-TS(1:10)}, input order preserved.</li>
 *   <li>BR-05: unmatched card or account is rejected and counted; the run ends RC 4.</li>
 * </ul>
 *
 * <p>The COBOL program only loads the first 500 cross-reference and account rows
 * ({@code OCCURS 500 TIMES}); that table limit is not reproduced here.
 */
@Service
public class TransferIntakeService {

    static final String TRANSFER_TYPE = "08";

    public IntakeResult extract(
            Iterable<DailyTransaction> transactions,
            List<CardCrossReference> crossReferences,
            List<AccountBook> accounts) {
        Map<String, String> accountByCard = new HashMap<>();
        for (CardCrossReference xref : crossReferences) {
            accountByCard.putIfAbsent(xref.cardNumber(), xref.accountId());
        }
        Map<Object, String> bookByAccount = new HashMap<>();
        for (AccountBook account : accounts) {
            bookByAccount.putIfAbsent(numericKey(account.accountId()), account.groupId());
        }

        long read = 0;
        List<TransferRequested> requested = new ArrayList<>();
        List<TransferRejected> rejected = new ArrayList<>();
        for (DailyTransaction tran : transactions) {
            read++;
            if (!TRANSFER_TYPE.equals(tran.typeCode())) {
                continue;
            }
            String sourceAccount = accountByCard.get(tran.cardNumber());
            if (sourceAccount == null) {
                rejected.add(new TransferRejected(
                        tran.tranId(), tran.cardNumber(), null,
                        TransferRejected.Reason.CARD_NOT_FOUND));
                continue;
            }
            String book = bookByAccount.get(numericKey(sourceAccount));
            if (book == null) {
                rejected.add(new TransferRejected(
                        tran.tranId(), tran.cardNumber(), sourceAccount,
                        TransferRejected.Reason.ACCOUNT_NOT_FOUND));
                continue;
            }
            requested.add(new TransferRequested(
                    tran.tranId(),
                    slice(tran.originTimestamp(), 0, 10),
                    sourceAccount,
                    slice(tran.description(), 13, 24),
                    book.stripTrailing(),
                    tran.amount(),
                    tran.cardNumber()));
        }
        return new IntakeResult(read, requested, rejected);
    }

    /** {@code XFR-SRC-ACCT-ID = WS-ACCT-ID} compares two PIC 9(11) items numerically. */
    private static Object numericKey(String accountId) {
        String digits = accountId.strip();
        if (!digits.isEmpty() && digits.chars().allMatch(Character::isDigit)) {
            return new BigInteger(digits);
        }
        return accountId;
    }

    /** COBOL reference modification over a space-padded field. */
    private static String slice(String value, int from, int to) {
        String padded = value.length() >= to ? value : String.format("%-" + to + "s", value);
        return padded.substring(from, to);
    }
}
