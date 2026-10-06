package com.carddemo.xfer.posting;

import com.carddemo.xfer.contracts.FeePolicy;
import com.carddemo.xfer.feepolicy.CobolFeePolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = {
    "com.carddemo.xfer.posting", "com.carddemo.xfer.support", "com.carddemo.xfer.kafkasupport"})
public class AccountPostingApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountPostingApplication.class, args);
    }

    @Bean
    FeePolicy feePolicy() {
        return new CobolFeePolicy();
    }
}
