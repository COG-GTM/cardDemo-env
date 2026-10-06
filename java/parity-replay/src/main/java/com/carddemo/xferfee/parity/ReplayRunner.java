package com.carddemo.xferfee.parity;

import java.nio.file.Files;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class ReplayRunner implements ApplicationRunner {

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
        replay.run(options).forEach((step, outcome) ->
                System.out.printf("parity-replay %s %s: %s%n", options.caseName(), step, outcome));
    }
}
