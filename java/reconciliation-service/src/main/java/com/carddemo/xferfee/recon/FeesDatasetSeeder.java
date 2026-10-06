package com.carddemo.xferfee.recon;

import java.nio.file.Path;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Feeds a recorded {@code XFER.FEES} dataset into the service as TransferPosted events. */
@Component
@ConditionalOnProperty("recon.seed.fees")
class FeesDatasetSeeder implements ApplicationRunner {

    private final ReconciliationService service;
    private final Path fees;
    private final LocalDate businessDate;

    FeesDatasetSeeder(ReconciliationService service,
            @Value("${recon.seed.fees}") Path fees,
            @Value("${recon.seed.business-date}") LocalDate businessDate) {
        this.service = service;
        this.fees = fees;
        this.businessDate = businessDate;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        new XferFeesDatasetReader().read(fees, businessDate).forEach(service::onPosted);
    }
}
