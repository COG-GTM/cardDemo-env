package com.carddemo.feeschedule;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fee-schedule")
public record FeeScheduleProperties(Path seedCsv) {
}
