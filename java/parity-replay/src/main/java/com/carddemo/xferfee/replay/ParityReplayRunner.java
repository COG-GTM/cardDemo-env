package com.carddemo.xferfee.replay;

import com.carddemo.xferfee.contracts.replay.ReplayStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Replays one fixture case through every {@link ReplayStage} bean in-process and writes a
 * candidate directory: jsonl/{DSN}.jsonl, db2_after/*.csv, sysout/*.txt and rc.json.
 */
@Component
class ParityReplayRunner implements ApplicationRunner {

    private final List<ReplayStage> stages;
    private final ObjectMapper mapper;

    ParityReplayRunner(List<ReplayStage> stages, ObjectMapper mapper) {
        this.stages = stages.stream().sorted(Comparator.comparingInt(ReplayStage::order)).toList();
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ReplayOptions options = ReplayOptions.parse(args.getSourceArgs());
        Files.createDirectories(options.out());
        CaseContext context = new CaseContext(mapper, options);
        Map<String, Integer> steps = new LinkedHashMap<>();
        int maxcc = 0;
        for (ReplayStage stage : stages) {
            int cc = stage.run(context);
            steps.merge(stage.step(), cc, Math::max);
            maxcc = Math.max(maxcc, cc);
        }
        context.writeTo(options.out());
        Map<String, Object> rc = new LinkedHashMap<>();
        rc.put("steps", steps);
        rc.put("maxcc", maxcc);
        Files.writeString(options.out().resolve("rc.json"),
                mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(rc) + "\n");
        System.out.printf("parity-replay: case=%s stages=%d maxcc=%d out=%s%n",
                options.caseName(), stages.size(), maxcc, options.out());
    }
}
