package com.carddemo.xferfee.services.common;

import org.springframework.boot.builder.SpringApplicationBuilder;

/** Each service reads {@code application.properties} plus its own {@code <name>.properties}. */
public final class ServiceLauncher {

    private ServiceLauncher() {
    }

    public static void run(Class<?> application, String configName, String[] args) {
        new SpringApplicationBuilder(application)
                .properties("spring.config.name=application," + configName)
                .run(args);
    }
}
