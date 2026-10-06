package com.carddemo.xferfee.intake;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.RejectReason;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferIntake;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * STEP010 / CBXFR01C: selects type-08 transfers and resolves card → source account → fee book (BR-01…BR-05).
 *
 * <p>Unlike CBXFR01C, the cross-reference and account tables have no 500-row limit.
 */
public class TransferIntakeService implements TransferIntake {

    public static final String STEP = "STEP010";
    public static final String TRANSFER_TYPE_CODE = "08";
    public static final int RC_OK = 0;
    public static final int RC_UNMATCHED = 4;

    static final String PROGRAM = "CBXFR01C: ";
    static final int CARD_NUM_LENGTH = 16;
    static final int ACCT_ID_LENGTH = 11;
    static final int DESC_LENGTH = 100;
    static final int TARGET_ACCT_OFFSET = 13;
    static final int ORIG_TS_LENGTH = 26;
    static final int TRAN_DATE_LENGTH = 10;

    @Override
    public IntakeResult extract(
            List<DailyTransaction> transactions, List<CardXref> xrefs, List<Account> accounts) {
        Map<String, Long> accountByCard = new HashMap<>();
        for (CardXref xref : xrefs) {
            accountByCard.putIfAbsent(xref.cardNumber(), xref.accountId());
        }
        Map<Long, String> bookByAccount = new HashMap<>();
        for (Account account : accounts) {
            bookByAccount.putIfAbsent(account.accountId(), account.groupId());
        }

        long read = 0;
        List<TransferRequested> selected = new ArrayList<>();
        List<TransferRejected> rejected = new ArrayList<>();
        List<String> sysout = new ArrayList<>();
        for (DailyTransaction tran : transactions) {
            read++;
            if (!TRANSFER_TYPE_CODE.equals(tran.typeCode())) {
                continue;
            }
            Long sourceAccountId = accountByCard.get(tran.cardNumber());
            if (sourceAccountId == null) {
                String detail = PROGRAM + "CARD NOT FOUND " + pad(tran.cardNumber(), CARD_NUM_LENGTH);
                reject(tran, RejectReason.UNMATCHED_CARD, detail, rejected, sysout);
                continue;
            }
            String book = bookByAccount.get(sourceAccountId);
            if (book == null) {
                String detail = PROGRAM + "ACCOUNT NOT FOUND " + accountId(sourceAccountId);
                reject(tran, RejectReason.UNKNOWN_SOURCE_ACCOUNT, detail, rejected, sysout);
                continue;
            }
            selected.add(new TransferRequested(
                    tran.tranId(),
                    tranDate(tran),
                    sourceAccountId,
                    targetAccountId(tran),
                    book,
                    tran.amount(),
                    tran.cardNumber()));
        }

        sysout.add(PROGRAM + "RECORDS READ " + counter(read));
        sysout.add(PROGRAM + "TRANSFERS SELECTED " + counter(selected.size()));
        sysout.add(PROGRAM + "UNMATCHED CARDS " + counter(rejected.size()));
        int returnCode = rejected.isEmpty() ? RC_OK : RC_UNMATCHED;
        return new IntakeResult(selected, rejected, new StepReport(STEP, returnCode, sysout));
    }

    private static void reject(DailyTransaction tran, RejectReason reason, String detail,
                               List<TransferRejected> rejected, List<String> sysout) {
        rejected.add(new TransferRejected(tran.tranId(), tran.cardNumber(), reason, detail));
        sysout.add(detail);
    }

    /** {@code TRAN-DESC(14:11)} moved to {@code XFR-TGT-ACCT-ID PIC 9(11)}. */
    static long targetAccountId(DailyTransaction tran) {
        String field = targetAccountField(tran.description());
        if (!field.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new UnrepresentableTransferException(tran.tranId(),
                    "TRAN-DESC(14:11) is not an 11-digit account id: '" + field + "'");
        }
        return Long.parseLong(field);
    }

    static String targetAccountField(String description) {
        return pad(description, DESC_LENGTH).substring(TARGET_ACCT_OFFSET, TARGET_ACCT_OFFSET + ACCT_ID_LENGTH);
    }

    /** {@code TRAN-ORIG-TS(1:10)} moved to {@code XFR-TRAN-DT}. */
    static LocalDate tranDate(DailyTransaction tran) {
        String field = pad(tran.originTimestamp(), ORIG_TS_LENGTH).substring(0, TRAN_DATE_LENGTH);
        try {
            return LocalDate.parse(field);
        } catch (DateTimeParseException e) {
            throw new UnrepresentableTransferException(tran.tranId(),
                    "TRAN-ORIG-TS(1:10) is not an ISO date: '" + field + "'");
        }
    }

    /** PIC 9(09) DISPLAY: zero-padded, high-order digits truncated. */
    static String counter(long value) {
        return String.format("%09d", value % 1_000_000_000L);
    }

    /** PIC 9(11) DISPLAY. */
    static String accountId(long value) {
        return String.format("%011d", value);
    }

    private static String pad(String value, int length) {
        String text = value == null ? "" : value;
        return text.length() >= length ? text.substring(0, length) : text + " ".repeat(length - text.length());
    }
}
