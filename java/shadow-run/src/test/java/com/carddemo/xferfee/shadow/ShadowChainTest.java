package com.carddemo.xferfee.shadow;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShadowChainTest {

    private static final List<FeeRule> RULES = List.of(
            rule("RETAIL", "0.012500", "25.00", "2020-01-01", "2024-06-15"),
            rule("RETAIL", "0.015000", "25.00", "2024-06-15", "9999-12-31"));
    private static final String CARD = "4000000000000001";
    private static final List<CardXref> XREF = List.of(new CardXref(CARD, 900000001, 1));

    private final ShadowChain chain = ShadowChain.legacy();

    @Test
    void halfUpRoundingThenStrictCap() {
        FeeRule rule = rule("RETAIL", "0.015000", "25.00", "2020-01-01", "9999-12-31");
        LegacyFeePolicy policy = new LegacyFeePolicy();
        assertThat(policy.apply(new BigDecimal("0.33"), rule)).isEqualTo(new FeeResult(new BigDecimal("0.00"), false));
        assertThat(policy.apply(new BigDecimal("1.00"), rule)).isEqualTo(new FeeResult(new BigDecimal("0.02"), false));
        assertThat(policy.apply(new BigDecimal("1666.67"), rule))
                .isEqualTo(new FeeResult(new BigDecimal("25.00"), false));
        assertThat(policy.apply(new BigDecimal("1700.00"), rule))
                .isEqualTo(new FeeResult(new BigDecimal("25.00"), true));
    }

    @Test
    void rateChangeWindowIsHalfOpen() {
        SnapshotFeeSchedule schedule = new SnapshotFeeSchedule();
        schedule.seed(RULES);
        assertThat(schedule.effectiveRule("RETAIL", LocalDate.parse("2024-06-14")).orElseThrow().feePct())
                .isEqualByComparingTo("0.0125");
        assertThat(schedule.effectiveRule("RETAIL", LocalDate.parse("2024-06-15")).orElseThrow().feePct())
                .isEqualByComparingTo("0.015");
        assertThat(schedule.effectiveRule("RETAIL", LocalDate.parse("2019-12-31"))).isEmpty();
    }

    @Test
    void unmatchedCardStopsTheJobAfterStep010WithRc4() {
        ChainResult result = chain.run(List.of(tran("T1", "08", "9999999999999999", "100.00", "2024-06-20")), XREF,
                accounts(), RULES, List.of());
        assertThat(result.stepRc()).containsExactly(Map.entry("STEP010", 4));
        assertThat(result.sysout().get("STEP010")).first().isEqualTo("CBXFR01C: CARD NOT FOUND 9999999999999999");
        assertThat(result.accountsOut()).isNull();
    }

    @Test
    void missingRuleAbendsWithRc8AndRollsBackTheLedger() {
        ChainResult result = chain.run(List.of(tran("T1", "08", CARD, "100.00", "2019-12-31")), XREF, accounts(),
                RULES, List.of());
        assertThat(result.stepRc()).containsEntry("STEP020", 8);
        assertThat(result.sysout().get("STEP020"))
                .containsExactly("XFERFEE: NO FEE RULE FOR BOOK RETAIL    ", "XFERFEE: 9999-ABEND-PROGRAM");
        assertThat(result.ledger()).isEmpty();
        assertThat(result.accountsOut()).isEmpty();
    }

    @Test
    void duplicateTranIdAbendsWithoutCommittingTheDay() {
        LedgerEntry existing = new LedgerEntry(String.format("%-16s", "T1"), LocalDate.parse("2024-06-01"), 1, 2,
                "RETAIL", new BigDecimal("1.00"), new BigDecimal("0.02"), false);
        ChainResult result = chain.run(List.of(tran("T1", "08", CARD, "100.00", "2024-06-20")), XREF, accounts(),
                RULES, List.of(existing));
        assertThat(result.stepRc()).containsEntry("STEP020", 8);
        assertThat(result.ledger()).containsExactly(existing);
    }

    @Test
    void postingDebitsSourceWithFeeAndCreditsTarget() {
        ChainResult result = chain.run(List.of(tran("T1", "08", CARD, "100.00", "2024-06-20")), XREF, accounts(),
                RULES, List.of());
        assertThat(result.stepRc()).containsExactly(Map.entry("STEP010", 0), Map.entry("STEP020", 0),
                Map.entry("STEP030", 0));
        assertThat(result.accountsOut().get(0).currentBalance()).isEqualByComparingTo("898.50");
        assertThat(result.accountsOut().get(0).currentCycleDebit()).isEqualByComparingTo("101.50");
        assertThat(result.accountsOut().get(1).currentBalance()).isEqualByComparingTo("1100.00");
        assertThat(result.accountsOut().get(1).currentCycleCredit()).isEqualByComparingTo("100.00");
        assertThat(result.reconReport()).last().asString().startsWith(" GRAND TOTAL COUNT");
    }

    @Test
    void emptyFeeDayWritesHeaderOnlyReportWithRc4() {
        ChainResult result = chain.run(List.of(tran("T1", "01", CARD, "100.00", "2024-06-20")), XREF, accounts(),
                RULES, List.of());
        assertThat(result.stepRc()).containsEntry("STEP030", 4);
        assertThat(result.reconReport()).hasSize(2);
        assertThat(result.sysout().get("STEP030")).containsExactly("CBXFR03C: NO FEE RECORDS");
    }

    @Test
    void zoneAndEditHelpersFollowCobolPictures() {
        assertThat(Zoned.decode("0000001000{", 2)).isEqualByComparingTo("100.00");
        assertThat(Zoned.decode("0000000012}", 2)).isEqualByComparingTo("-1.20");
        assertThat(Zoned.decode("00000000012p", 2)).isEqualByComparingTo("-1.20");
        assertThat(Zoned.editedMoney(new BigDecimal("100.00"))).isEqualTo("      100.00 ");
        assertThat(Zoned.editedMoney(new BigDecimal("-0.05"))).isEqualTo("        0.05-");
        assertThat(Zoned.signedDisplay(new BigDecimal("6.50"), 9, 2)).isEqualTo("+00000000650");
        assertThat(Zoned.truncate(new BigDecimal("12345678901.23"), 9, 2)).isEqualByComparingTo("345678901.23");
        assertThat(new String(new FixedRecord(12).computedZoned(new BigDecimal("-1.25"), 10, 2).bytes()))
                .isEqualTo("00000000012u");
    }

    @Test
    void gnuCobolReadsEbcdicOverpunchAsPositiveZeroDigit() {
        assertThat(Zoned.gnuDecode("0000016666F", 2)).isEqualByComparingTo("1666.60");
        assertThat(Zoned.gnuDecode("0000016666{", 2)).isEqualByComparingTo("1666.60");
        assertThat(Zoned.gnuDecode("0000016666J", 2)).isEqualByComparingTo("1666.60");
        assertThat(Zoned.gnuDecode("00000166666", 2)).isEqualByComparingTo("1666.66");
        assertThat(Zoned.gnuDecode("0000016666v", 2)).isEqualByComparingTo("-1666.66");
        assertThat(Zoned.gnuReadBack(new BigDecimal("1666.66"), 2)).isEqualByComparingTo("1666.60");
        assertThat(Zoned.gnuReadBack(new BigDecimal("-12.34"), 2)).isEqualByComparingTo("12.30");
        assertThat(Zoned.gnuReadBack(new BigDecimal("0.07"), 2)).isEqualByComparingTo("0.00");
    }

    @Test
    void subCentLastDigitIsDroppedBeforeFeeAndPosting() {
        ChainResult result = chain.run(List.of(tran("T1", "08", CARD, "1666.66", "2024-06-20")), XREF, accounts(),
                RULES, List.of());
        assertThat(result.extract().get(0).amount()).isEqualByComparingTo("1666.66");
        assertThat(result.posted().get(0).amount()).isEqualByComparingTo("1666.60");
        assertThat(result.posted().get(0).feeAmount()).isEqualByComparingTo("25.00");
        assertThat(result.accountsOut().get(1).currentBalance()).isEqualByComparingTo("2666.60");
    }

    private static List<Account> accounts() {
        List<Account> accounts = new ArrayList<>();
        for (long id = 1; id <= 2; id++) {
            accounts.add(new Account(id, "Y", new BigDecimal("1000.00"), new BigDecimal("5000.00"),
                    new BigDecimal("1000.00"), "2020-01-01", "2030-12-31", "2025-01-01", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), "10001     ", "RETAIL    "));
        }
        return accounts;
    }

    private static DailyTransaction tran(String id, String type, String card, String amt, String date) {
        String desc = String.format("%-100s", "XFER TO ACCT 00000000002");
        return new DailyTransaction(String.format("%-16s", id), type, 1, "DEMO      ", desc, new BigDecimal(amt), 1,
                "M", "C", "00000     ", card, date + " 12:00:00.000000   ", date + " 12:01:00.000000   ");
    }

    private static FeeRule rule(String book, String pct, String cap, String eff, String exp) {
        return new FeeRule(book, new BigDecimal(pct), new BigDecimal(cap), LocalDate.parse(eff), LocalDate.parse(exp));
    }
}
