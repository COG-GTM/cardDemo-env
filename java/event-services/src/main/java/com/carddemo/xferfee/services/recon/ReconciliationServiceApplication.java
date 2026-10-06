package com.carddemo.xferfee.services.recon;

import com.carddemo.xferfee.services.common.ServiceLauncher;
import com.carddemo.xferfee.services.common.ServiceSupport;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** CBXFR03C as a consumer of {@code xferfee.transfer-posted}; publishes {@code RunCompleted}. */
@SpringBootApplication
@Import(ServiceSupport.class)
public class ReconciliationServiceApplication {

    public static void main(String[] args) {
        ServiceLauncher.run(ReconciliationServiceApplication.class, "reconciliation", args);
    }
}
