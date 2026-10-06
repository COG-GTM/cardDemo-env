package com.carddemo.xfer.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = {
    "com.carddemo.xfer.relay", "com.carddemo.xfer.support", "com.carddemo.xfer.kafkasupport"})
public class OutboxRelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(OutboxRelayApplication.class, args);
    }
}
