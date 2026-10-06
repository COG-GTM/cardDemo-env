package com.carddemo.xferfee.services.intake;

import com.carddemo.xferfee.services.common.ServiceLauncher;
import com.carddemo.xferfee.services.common.ServiceSupport;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** CBXFR01C as a consumer of {@code xferfee.daily-transactions}. */
@SpringBootApplication
@Import(ServiceSupport.class)
public class TransferIntakeServiceApplication {

    public static void main(String[] args) {
        ServiceLauncher.run(TransferIntakeServiceApplication.class, "transfer-intake", args);
    }
}
