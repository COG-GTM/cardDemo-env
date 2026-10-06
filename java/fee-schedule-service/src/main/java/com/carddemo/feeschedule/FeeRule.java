package com.carddemo.feeschedule;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One CTL_XFER_PARM row: the fee rate and cap for a book, valid on {@code [effDt, expDt)}.
 * {@code bookId} is held without the CHAR(10) blank padding.
 */
public record FeeRule(
        @NotBlank @Size(max = FeeRule.BOOK_ID_LENGTH) String bookId,
        @NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feePct,
        @NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feeCap,
        @NotNull LocalDate effDt,
        @NotNull LocalDate expDt) {

    public static final int BOOK_ID_LENGTH = 10;

    public FeeRule {
        if (bookId != null) {
            bookId = bookId.stripTrailing();
        }
    }

    public boolean coversDate(LocalDate date) {
        return !effDt.isAfter(date) && expDt.isAfter(date);
    }

    public boolean overlaps(FeeRule other) {
        return bookId.equals(other.bookId)
                && effDt.isBefore(other.expDt)
                && other.effDt.isBefore(expDt);
    }
}
