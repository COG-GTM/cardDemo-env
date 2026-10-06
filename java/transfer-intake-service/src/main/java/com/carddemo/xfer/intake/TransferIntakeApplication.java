package com.carddemo.xfer.intake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
    "com.carddemo.xfer.intake", "com.carddemo.xfer.support", "com.carddemo.xfer.kafkasupport"})
public class TransferIntakeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransferIntakeApplication.class, args);
    }
}
