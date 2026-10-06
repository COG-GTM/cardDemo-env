package com.carddemo.xfer.recon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
    "com.carddemo.xfer.recon", "com.carddemo.xfer.support", "com.carddemo.xfer.kafkasupport"})
public class ReconciliationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconciliationApplication.class, args);
    }
}
