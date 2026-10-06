package com.carddemo.xferfee.feeschedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=fee-schedule-service")
class FeeScheduleIntegrationTest {

    /** Same engine and major version as the estate's compose database. */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    static final Path FIXTURES = locateFixtures();

    @Autowired
    TestRestTemplate http;

    @Autowired
    FeeSchedule schedule;

    @BeforeEach
    void seedDefaultFixture() throws Exception {
        FeeRuleSnapshots.seed(schedule, FIXTURES.resolve("default/db2_before"));
    }

    @Test
    void serviceUsesJdbcBackedSchedule() {
        assertThat(schedule).isInstanceOf(JdbcFeeSchedule.class);
    }

    @ParameterizedTest(name = "RETAIL on {0} -> {1} from {2}")
    @CsvSource({
            "2024-06-14, 0.012500, 2020-01-01",
            "2024-06-15, 0.015000, 2024-06-15",
            "2024-06-16, 0.015000, 2024-06-15",
            "2020-01-01, 0.012500, 2020-01-01",
            "9999-12-30, 0.015000, 2024-06-15",
    })
    void retailBoundaryDatesUseHalfOpenWindow(String date, String pct, String effDt) {
        ResponseEntity<FeeRule> response = effective("RETAIL", date);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        FeeRule rule = response.getBody();
        assertThat(rule.bookId()).isEqualTo("RETAIL");
        assertThat(rule.feePct()).isEqualByComparingTo(pct);
        assertThat(rule.feeCap()).isEqualByComparingTo("25.00");
        assertThat(rule.effectiveDate()).isEqualTo(LocalDate.parse(effDt));
    }

    @Test
    void instlRuleIsUnaffectedByRetailRateChange() {
        for (String date : new String[] {"2024-06-14", "2024-06-15", "2024-06-16"}) {
            FeeRule rule = effective("INSTL", date).getBody();
            assertThat(rule.feePct()).isEqualByComparingTo("0.005000");
            assertThat(rule.feeCap()).isEqualByComparingTo("500.00");
        }
    }

    @ParameterizedTest
    @CsvSource({"RETAIL, 2019-12-31", "RETAIL, 9999-12-31", "UNKNOWN, 2024-06-15"})
    void noEffectiveRuleIs404(String book, String date) {
        assertThat(effective(book, date).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void paddedBookIdMatchesLikeChar10() {
        assertThat(effective("RETAIL    ", "2024-06-15").getBody().feePct())
                .isEqualByComparingTo("0.015000");
    }

    @Test
    void invalidRequestsAre400() {
        assertThat(get("/fee-rules/effective?book=RETAIL&date=2024-13-01").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/fee-rules/effective?book=RETAIL").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/fee-rules/effective?book=ELEVENCHARS&date=2024-06-15").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listReturnsRulesInRecorderOrder() {
        FeeRule[] rules = http.getForObject("/fee-rules", FeeRule[].class);
        assertThat(rules).extracting(FeeRule::bookId).containsExactly("INSTL", "RETAIL", "RETAIL");
    }

    @Test
    void insertRejectsOverlappingWindow() {
        FeeRule overlapping = rule("RETAIL", "0.020000", "2024-07-01", "9999-12-31");
        ResponseEntity<Map> response = http.postForEntity("/fee-rules", overlapping, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(effective("RETAIL", "2024-07-01").getBody().feePct())
                .isEqualByComparingTo("0.015000");
    }

    @Test
    void insertRejectsDuplicateKeyAndEmptyWindow() {
        assertThat(http.postForEntity("/fee-rules",
                rule("INSTL", "0.001000", "2020-01-01", "2020-01-02"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.postForEntity("/fee-rules",
                rule("CORP", "0.001000", "2024-06-15", "2024-06-15"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/fee-rules",
                rule("CORP", "0.0000001", "2024-06-15", "2024-06-16"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void insertAcceptsAdjacentWindow() {
        FeeRule corpOld = rule("CORP", "0.010000", "2020-01-01", "2024-06-15");
        FeeRule corpNew = rule("CORP", "0.011000", "2024-06-15", "9999-12-31");
        assertThat(http.postForEntity("/fee-rules", corpOld, FeeRule.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(http.postForEntity("/fee-rules", corpNew, FeeRule.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(effective("CORP", "2024-06-14").getBody().feePct()).isEqualByComparingTo("0.010000");
        assertThat(effective("CORP", "2024-06-15").getBody().feePct()).isEqualByComparingTo("0.011000");
    }

    @Test
    void legacyOverlapLoadedBySeedIsReportedAsAmbiguous(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(FeeRuleSnapshots.TABLE_FILE), """
                book_id,fee_pct,fee_cap,eff_dt,exp_dt
                RETAIL    ,0.012500,25.00,2020-01-01,9999-12-31
                RETAIL    ,0.015000,25.00,2024-06-15,9999-12-31
                """);
        FeeRuleSnapshots.seed(schedule, dir);
        assertThat(get("/fee-rules/effective?book=RETAIL&date=2024-06-14").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get("/fee-rules/effective?book=RETAIL&date=2024-06-15").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureCases")
    void replayLeavesDb2AfterByteEqual(String fixtureCase, @TempDir Path out) throws Exception {
        Path caseDir = FIXTURES.resolve(fixtureCase);
        FeeRuleSnapshots.seed(schedule, caseDir.resolve("db2_before"));
        Path dumped = FeeRuleSnapshots.dump(schedule, out);
        assertThat(Files.readString(dumped)).isEqualTo(
                Files.readString(caseDir.resolve("expected/db2_after/" + FeeRuleSnapshots.TABLE_FILE)));
    }

    static Stream<String> fixtureCases() throws Exception {
        try (Stream<Path> cases = Files.list(FIXTURES)) {
            return cases.filter(p -> Files.exists(p.resolve("db2_before/" + FeeRuleSnapshots.TABLE_FILE)))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    private ResponseEntity<FeeRule> effective(String book, String date) {
        return http.getForEntity("/fee-rules/effective?book={book}&date={date}",
                FeeRule.class, book, date);
    }

    private ResponseEntity<String> get(String uri) {
        return http.getForEntity(uri, String.class);
    }

    private static FeeRule rule(String book, String pct, String eff, String exp) {
        return new FeeRule(book, new BigDecimal(pct), new BigDecimal("25.00"),
                LocalDate.parse(eff), LocalDate.parse(exp));
    }

    private static Path locateFixtures() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("fixtures/xferfee");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("fixtures/xferfee not found above " + Path.of("").toAbsolutePath());
    }
}
