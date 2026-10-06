package com.carddemo.xfer.posting;

import com.carddemo.xfer.contracts.FeeRule;
import com.carddemo.xfer.contracts.XferJson;
import java.net.URI;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Calls fee-schedule-service; network failures propagate so Kafka redelivers. */
@Component
public class FeeScheduleClient {

    private final RestTemplate http = new RestTemplate();
    private final String baseUrl;

    public FeeScheduleClient(@Value("${xfer.fee-schedule.url}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Empty when no rule is effective (SQLCODE +100); throws PostingAbend on -811 or bad input. */
    public Optional<FeeRule> effectiveRule(String bookId, String tranDt) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/fee-rules/effective")
                .queryParam("bookId", bookId)
                .queryParam("date", tranDt)
                .encode()
                .build()
                .toUri();
        try {
            return Optional.of(XferJson.read(http.getForObject(uri, String.class), FeeRule.class));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new PostingAbend("XFERFEE: RULE LOOKUP FAILED " + e.getStatusCode().value());
        }
    }
}
