package com.carddemo.parity.console;

import com.carddemo.parity.engine.CaseInputs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ConsoleController {

    /** COG-1250 demo cases first, then the rest of the parity suite. */
    static final List<String> FEATURED = List.of("default", "half_cent", "rate_change", "at_cap");
    private static final Pattern CASE_NAME = Pattern.compile("[a-z_]+");

    private final ConsoleProperties properties;
    private final ParityRunService service;

    public ConsoleController(ConsoleProperties properties, ParityRunService service) {
        this.properties = properties;
        this.service = service;
    }

    @GetMapping("/api/cases")
    public List<Map<String, Object>> cases() throws IOException {
        List<String> names = new ArrayList<>(FEATURED);
        try (var dirs = Files.list(properties.fixtures())) {
            dirs.filter(d -> Files.isDirectory(d.resolve("input")))
                    .map(Path::getFileName).map(Path::toString).sorted()
                    .filter(n -> !names.contains(n)).forEach(names::add);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : names) {
            if (!Files.isDirectory(properties.fixtures().resolve(name))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("featured", FEATURED.contains(name));
            row.put("feedSize", CaseInputs.load(properties.fixtures(), name).feed().size());
            out.add(row);
        }
        return out;
    }

    @GetMapping("/api/config")
    public Map<String, Object> config() {
        return Map.of("repoRoot", properties.root().toString(), "cobolCommand", properties.cobolCommand());
    }

    @GetMapping(path = "/api/run", produces = "text/event-stream")
    public SseEmitter run(@RequestParam("case") String caseName,
                          @RequestParam(defaultValue = "false") boolean breakIt,
                          @RequestParam(defaultValue = "400") long delayMs) {
        if (!CASE_NAME.matcher(caseName).matches()
                || !Files.isDirectory(properties.fixtures().resolve(caseName).resolve("input"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown case " + caseName);
        }
        return service.start(caseName, breakIt, Math.max(0, Math.min(delayMs, 5000)));
    }
}
