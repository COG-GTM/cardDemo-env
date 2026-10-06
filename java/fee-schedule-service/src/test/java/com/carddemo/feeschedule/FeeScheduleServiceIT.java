package com.carddemo.feeschedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeeScheduleServiceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    TestRestTemplate http;

    @Autowired
    FeeScheduleService service;

    @Autowired
    FeeRuleRepository repository;

    @BeforeEach
    void seedRateChangeFixture() {
        service.replaceAll(FeeRuleCsv.read(Fixtures.before("rate_change")));
    }

    @ParameterizedTest(name = "RETAIL on {0} -> {1} from {2}")
    @CsvSource({
            "2024-06-14, 0.012500, 2020-01-01, 2024-06-15",
            "2024-06-15, 0.015000, 2024-06-15, 9999-12-31",
            "2024-06-16, 0.015000, 2024-06-15, 9999-12-31",
    })
    void picksRuleByHalfOpenWindowAroundRateChange(String date, String pct, String eff, String exp) {
        ResponseEntity<Map> response = effective("RETAIL", date);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("bookId", "RETAIL")
                .containsEntry("feePct", pct)
                .containsEntry("feeCap", "25.00")
                .containsEntry("effDt", eff)
                .containsEntry("expDt", exp);
    }

    @Test
    void matchesCharPaddedBookIds() {
        assertThat(effective("RETAIL    ", "2024-06-15").getBody()).containsEntry("feePct", "0.015000");
        assertThat(effective("INSTL", "2024-06-15").getBody())
                .containsEntry("feePct", "0.005000")
                .containsEntry("feeCap", "500.00");
    }

    @ParameterizedTest(name = "{0} on {1} -> 404")
    @CsvSource({
            "RETAIL, 2019-12-31",
            "RETAIL, 9999-12-31",
            "CORP,   2024-06-15",
    })
    void returns404WhenNoRuleIsEffective(String book, String date) {
        assertThat(effective(book, date).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsMalformedQueries() {
        assertThat(effective("RETAIL", "2024-13-01").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(effective("RETAIL_BOOK_", "2024-06-15").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(effective(" ", "2024-06-15").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void returns409WhenLegacyDataHasOverlappingRules() {
        repository.insert(rule("RETAIL", "0.020000", "2024-06-01", "2024-07-01"));

        assertThat(effective("RETAIL", "2024-06-15").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(effective("RETAIL", "2024-07-01").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void insertRejectsOverlappingWindows() {
        ResponseEntity<Map> overlap = post(rule("RETAIL", "0.020000", "2024-06-01", "2024-07-01"));
        ResponseEntity<Map> empty = post(rule("CORP", "0.020000", "2024-07-01", "2024-07-01"));
        ResponseEntity<Map> adjacent = post(rule("CORP", "0.020000", "2024-07-01", "9999-12-31"));

        assertThat(overlap.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(adjacent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(effective("CORP", "2024-07-01").getBody()).containsEntry("feePct", "0.020000");
        assertThat(repository.findByBook("RETAIL")).hasSize(2);
    }

    static List<String> cases() {
        return Fixtures.cases().toList();
    }

    @ParameterizedTest
    @MethodSource("cases")
    void replayLeavesCtlXferParmByteEqual(String caseName) throws Exception {
        service.replaceAll(FeeRuleCsv.read(Fixtures.before(caseName)));

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.parseMediaType(FeeRuleCsv.MEDIA_TYPE)));
        ResponseEntity<byte[]> csv = http.exchange(
                "/fee-rules", HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(csv.getBody(), StandardCharsets.US_ASCII))
                .isEqualTo(Files.readString(Fixtures.expectedAfter(caseName), StandardCharsets.US_ASCII));
    }

    private ResponseEntity<Map> effective(String book, String date) {
        return http.getForEntity("/fee-rules/effective?book={book}&date={date}", Map.class, book, date);
    }

    private ResponseEntity<Map> post(FeeRule rule) {
        return http.postForEntity("/fee-rules", rule, Map.class);
    }

    private static FeeRule rule(String book, String pct, String eff, String exp) {
        return new FeeRule(book, new BigDecimal(pct), new BigDecimal("25.00"),
                LocalDate.parse(eff), LocalDate.parse(exp));
    }
}
