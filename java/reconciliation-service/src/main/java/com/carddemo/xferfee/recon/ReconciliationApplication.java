package com.carddemo.xferfee.recon;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ReconciliationApplication {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "replay".equals(args[0])) {
            System.exit(ReconReplay.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        SpringApplication.run(ReconciliationApplication.class, args);
    }
}
