package com.carddemo.xfer.support;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration
@PropertySource("classpath:xfer-defaults.properties")
public class XferSupportConfig {
}
