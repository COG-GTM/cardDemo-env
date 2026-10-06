package com.carddemo.xferfee.services.posting;

import com.carddemo.xferfee.services.common.ServiceLauncher;
import com.carddemo.xferfee.services.common.ServiceSupport;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** XFERFEE posting as a consumer of {@code xferfee.transfer-requested}; fee rules from fee-schedule-service. */
@SpringBootApplication
@Import(ServiceSupport.class)
public class AccountPostingServiceApplication {

    public static void main(String[] args) {
        ServiceLauncher.run(AccountPostingServiceApplication.class, "account-posting", args);
    }
}
