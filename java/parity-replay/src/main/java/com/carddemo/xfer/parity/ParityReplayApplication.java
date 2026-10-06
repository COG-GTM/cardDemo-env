package com.carddemo.xfer.parity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.carddemo.xfer")
public class ParityReplayApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(ParityReplayApplication.class, args)));
    }
}
