package com.carddemo.xferfee.replay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Usage: {@code java -jar parity-replay.jar --case <name> --in <dir> --out <dir>}.
 *
 * <p>{@code --in} holds the JSON-lines inputs written by {@code tools/parity/java_candidate.py};
 * {@code --out} receives the JSON-lines outputs it encodes back into the candidate layout.
 */
@SpringBootApplication(scanBasePackages = "com.carddemo.xferfee")
public class ParityReplayApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(ParityReplayApplication.class, args)));
    }
}
