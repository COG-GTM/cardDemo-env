package com.carddemo.parity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Replays one xferfee fixture through the Java services and writes JSON-lines outputs that
 * tools/parity/java_candidate.py encodes back to fixed-width datasets for compare.py.
 */
@SpringBootApplication(scanBasePackages = "com.carddemo")
public class ParityReplayApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(ParityReplayApplication.class, args)));
    }
}
