package com.carddemo.xferfee.services.posting;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link FeeSchedule} over fee-schedule-service's REST API. */
@Component
public class RemoteFeeSchedule implements FeeSchedule {

    private final RestClient client;

    public RemoteFeeSchedule(RestClient.Builder builder, @Value("${xferfee.fee-schedule-url}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    @Override
    public void seed(List<FeeRule> rules) {
        client.put().uri("/api/fee-rules").body(rules).retrieve().toBodilessEntity();
    }

    @Override
    public Optional<FeeRule> effectiveRule(String bookId, LocalDate businessDate) {
        return Optional.ofNullable(client.get()
                .uri(uri -> uri.path("/api/fee-rules/effective")
                        .queryParam("bookId", bookId.stripTrailing())
                        .queryParam("date", businessDate)
                        .build())
                .exchange((request, response) -> response.getStatusCode() == HttpStatus.NOT_FOUND
                        ? null
                        : response.bodyTo(FeeRule.class)));
    }

    @Override
    public List<FeeRule> rules() {
        FeeRule[] rules = client.get().uri("/api/fee-rules").retrieve().body(FeeRule[].class);
        return rules == null ? List.of() : Arrays.asList(rules);
    }

}
