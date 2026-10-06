package com.carddemo.parity.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** A live session with {@code feed.py}: one real XFRDAILY run on GnuCOBOL per transaction. */
public final class CobolChain implements AutoCloseable {

    private final Process process;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;
    private final ObjectMapper mapper;
    private final JsonNode ready;

    private CobolChain(Process process, ObjectMapper mapper) throws IOException {
        this.process = process;
        this.mapper = mapper;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.ready = readEvent();
        if (!"ready".equals(ready.path("event").asText())) {
            throw new IOException("COBOL feed failed to start: " + ready);
        }
    }

    public static CobolChain start(String command, Path workDir, String caseName, ObjectMapper mapper)
            throws IOException {
        List<String> argv = new ArrayList<>(List.of(command.trim().split("\\s+")));
        argv.add("--case");
        argv.add(caseName);
        Process process = new ProcessBuilder(argv)
                .directory(workDir.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        return new CobolChain(process, mapper);
    }

    public JsonNode ready() {
        return ready;
    }

    public JsonNode run(int seq, byte[] record) throws IOException {
        stdin.write(mapper.writeValueAsString(Map.of("seq", seq, "record", Base64.getEncoder().encodeToString(record))));
        stdin.newLine();
        stdin.flush();
        return readEvent();
    }

    private JsonNode readEvent() throws IOException {
        String line;
        while ((line = stdout.readLine()) != null) {
            if (line.startsWith("{")) {
                return mapper.readTree(line);
            }
        }
        throw new IOException("COBOL feed exited (code " + exitCode() + ")");
    }

    private String exitCode() {
        try {
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            return process.isAlive() ? "running" : Integer.toString(process.exitValue());
        } catch (IllegalThreadStateException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    @Override
    public void close() {
        try {
            stdin.write("{\"cmd\":\"quit\"}");
            stdin.newLine();
            stdin.flush();
        } catch (IOException ignored) {
            // process already gone
        }
        process.destroy();
    }
}
