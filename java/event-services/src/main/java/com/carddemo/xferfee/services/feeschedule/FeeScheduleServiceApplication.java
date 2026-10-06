package com.carddemo.xferfee.services.feeschedule;

import com.carddemo.xferfee.services.common.ServiceLauncher;
import com.carddemo.xferfee.services.common.ServiceSupport;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** CTL_XFER_PARM as a service: effective-dated rule lookup over REST, backed by Postgres. */
@SpringBootApplication
@Import(ServiceSupport.class)
public class FeeScheduleServiceApplication {

    public static void main(String[] args) {
        ServiceLauncher.run(FeeScheduleServiceApplication.class, "fee-schedule", args);
    }
}
