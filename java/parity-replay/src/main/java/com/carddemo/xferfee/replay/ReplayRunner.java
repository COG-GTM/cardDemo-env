package com.carddemo.xferfee.replay;

import java.nio.file.Files;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class ReplayRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayRunner.class);

    private final ChainReplay replay;

    ReplayRunner(ChainReplay replay) {
        this.replay = replay;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("case")) {
            return;
        }
        ReplayOptions options = ReplayOptions.from(args);
        if (!Files.isDirectory(options.in())) {
            throw new IllegalArgumentException("--in is not a directory: " + options.in());
        }
        Files.createDirectories(options.out());
        Map<String, String> status = replay.run(options);
        status.forEach((step, outcome) -> LOG.info("{} {}: {}", options.caseName(), step, outcome));
    }
}
