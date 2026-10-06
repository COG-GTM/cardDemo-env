package com.carddemo.xferfee.feeschedule;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FeeScheduleApplication {

    /** Service config lives in {@code fee-schedule-service.yml}, not {@code application.yml}. */
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(FeeScheduleApplication.class);
        app.setDefaultProperties(Map.of("spring.config.name", "fee-schedule-service"));
        app.run(args);
    }
}
