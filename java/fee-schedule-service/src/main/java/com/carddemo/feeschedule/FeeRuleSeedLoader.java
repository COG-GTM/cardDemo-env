package com.carddemo.feeschedule;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Loads a fixture's db2_before/CTL_XFER_PARM.csv when {@code fee-schedule.seed-csv} is set. */
@Component
public class FeeRuleSeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FeeRuleSeedLoader.class);

    private final FeeScheduleProperties properties;
    private final FeeScheduleService service;

    public FeeRuleSeedLoader(FeeScheduleProperties properties, FeeScheduleService service) {
        this.properties = properties;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.seedCsv() == null || properties.seedCsv().toString().isBlank()) {
            return;
        }
        List<FeeRule> rules = FeeRuleCsv.read(properties.seedCsv());
        service.replaceAll(rules);
        log.info("Seeded {} fee rules from {}", rules.size(), properties.seedCsv());
    }
}
