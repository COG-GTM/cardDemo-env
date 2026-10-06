package com.carddemo.xferfee.replay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.carddemo.xferfee")
public class ParityReplayApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(ParityReplayApplication.class, args)));
    }
}
