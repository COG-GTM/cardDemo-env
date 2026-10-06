package com.carddemo.xfer.feeschedule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.carddemo.xfer.feeschedule", "com.carddemo.xfer.support"})
public class FeeScheduleApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeeScheduleApplication.class, args);
    }
}
