package com.carddemo.xferfee.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.port.IntakeResult;
import com.carddemo.xferfee.contracts.port.StepReport;
import com.carddemo.xferfee.contracts.port.TransferIntake;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class ChainReplayTest {

    @TempDir
    Path dir;

    @Test
    void noStageBeansStillWritesRcAndStatus() throws Exception {
        Path out = dir.resolve("out");
        ChainReplay replay = replay(new StaticListableBeanFactory());

        replay.run(new ReplayOptions("default", Files.createDirectories(dir.resolve("in")), out));

        assertThat(Files.readString(out.resolve("rc.json"))).contains("\"maxcc\" : 0").contains("\"steps\" : { }");
        assertThat(Files.readString(out.resolve("replay.json")))
                .contains("NOT IMPLEMENTED: no TransferIntake bean")
                .contains("\"STEP030\" : \"NOT RUN");
        assertThat(out.resolve("XFER.EXTRACT.jsonl")).doesNotExist();
    }

    @Test
    void implementedStagesRunUntilTheFirstMissingOne() throws Exception {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("intake", (TransferIntake) (transactions, xrefs, accounts) -> new IntakeResult(
                transactions.size(), List.of(), List.of(),
                new StepReport("STEP010", 0, List.of("CBXFR01C: RECORDS READ 000000000"))));
        Path out = dir.resolve("out");

        replay(beans).run(new ReplayOptions("default", Files.createDirectories(dir.resolve("in")), out));

        assertThat(out.resolve("XFER.EXTRACT.jsonl")).exists();
        assertThat(Files.readAllLines(out.resolve("sysout/STEP010.txt")))
                .containsExactly("CBXFR01C: RECORDS READ 000000000");
        assertThat(Files.readString(out.resolve("rc.json"))).contains("\"STEP010\" : 0");
        assertThat(Files.readString(out.resolve("replay.json")))
                .contains("NOT IMPLEMENTED: no AccountPosting bean");
    }

    @Test
    void aThrowingStageIsReportedNotPropagated() throws Exception {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("intake", (TransferIntake) (transactions, xrefs, accounts) -> {
            throw new IllegalStateException("boom");
        });
        Path out = dir.resolve("out");

        replay(beans).run(new ReplayOptions("default", Files.createDirectories(dir.resolve("in")), out));

        assertThat(Files.readString(out.resolve("replay.json"))).contains("FAILED: java.lang.IllegalStateException: boom");
    }

    private static ChainReplay replay(StaticListableBeanFactory beans) {
        return new ChainReplay(beans.getBeanProvider(com.carddemo.xferfee.contracts.port.TransferIntake.class),
                beans.getBeanProvider(com.carddemo.xferfee.contracts.port.AccountPosting.class),
                beans.getBeanProvider(com.carddemo.xferfee.contracts.port.Reconciliation.class));
    }
}
