package com.carddemo.parity;

import com.carddemo.parity.candidate.CandidateCli;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
public class ParityConsoleApplication {

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--candidate")) {
            System.exit(CandidateCli.run(args));
        }
        SpringApplication.run(ParityConsoleApplication.class, args);
    }
}
