package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.TransferIntake;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ReplayConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayConfiguration.class);

    @Bean
    ReplayEngine replayEngine(
            ObjectProvider<FeeSchedule> feeSchedule,
            ObjectProvider<TransferIntake> intake,
            ObjectProvider<AccountPosting> posting,
            ObjectProvider<Reconciliation> reconciliation) {
        return new ReplayEngine(
                Optional.ofNullable(feeSchedule.getIfUnique()),
                Optional.ofNullable(intake.getIfUnique()),
                Optional.ofNullable(posting.getIfUnique()),
                Optional.ofNullable(reconciliation.getIfUnique()));
    }

    @Bean
    ReplayRunner replayRunner(ReplayEngine engine) {
        return new ReplayRunner(engine);
    }

    static class ReplayRunner implements ApplicationRunner, ExitCodeGenerator {

        private final ReplayEngine engine;
        private int exitCode;

        ReplayRunner(ReplayEngine engine) {
            this.engine = engine;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            List<String> raw = List.of(args.getSourceArgs()).stream()
                    .filter(arg -> !arg.startsWith("--spring.") && !arg.startsWith("--xferfee."))
                    .toList();
            if (raw.isEmpty()) {
                return;
            }
            try {
                engine.run(ReplayOptions.parse(raw));
            } catch (IllegalArgumentException e) {
                LOG.error(e.getMessage());
                exitCode = 2;
            }
        }

        @Override
        public int getExitCode() {
            return exitCode;
        }
    }
}
