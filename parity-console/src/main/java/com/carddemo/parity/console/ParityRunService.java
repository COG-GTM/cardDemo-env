package com.carddemo.parity.console;

import com.carddemo.parity.engine.CaseInputs;
import com.carddemo.parity.engine.FeeRounding;
import com.carddemo.parity.engine.TransferOutcome;
import com.carddemo.parity.engine.XferFeeEngine;
import com.carddemo.parity.records.DailyTransaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Streams a fixture feed through GnuCOBOL and the Java engine side by side, one transaction at a time. */
@Service
public class ParityRunService {

    private static final Logger LOG = LoggerFactory.getLogger(ParityRunService.class);

    private final ConsoleProperties properties;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ReentrantLock running = new ReentrantLock();

    public ParityRunService(ConsoleProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public SseEmitter start(String caseName, boolean breakIt, long delayMs) {
        SseEmitter emitter = new SseEmitter(15 * 60 * 1000L);
        executor.submit(() -> {
            if (!running.tryLock()) {
                send(emitter, "failed", Map.of("message", "another run is in progress"));
                emitter.complete();
                return;
            }
            try {
                run(caseName, breakIt, delayMs, emitter);
            } catch (Exception e) {
                LOG.warn("parity run failed", e);
                send(emitter, "failed", Map.of("message", String.valueOf(e.getMessage())));
            } finally {
                running.unlock();
                emitter.complete();
            }
        });
        return emitter;
    }

    private void run(String caseName, boolean breakIt, long delayMs, SseEmitter emitter) throws Exception {
        CaseInputs inputs = CaseInputs.load(properties.fixtures(), caseName);
        FeeRounding rounding = FeeRounding.of(breakIt);
        XferFeeEngine java = inputs.newEngine(rounding);
        Totals totals = new Totals();
        try (CobolChain cobol = CobolChain.start(properties.cobolCommand(), properties.root(), caseName, mapper)) {
            Map<String, Object> start = new LinkedHashMap<>();
            start.put("case", caseName);
            start.put("rounding", rounding.name());
            start.put("javaRoundingMode", rounding.mode().name());
            start.put("cobolEngine", cobol.ready().path("engine").asText());
            start.put("feedSize", inputs.feed().size());
            start.put("accounts", cobol.ready().path("accounts"));
            send(emitter, "start", start);

            List<DailyTransaction> feed = inputs.feed();
            for (int i = 0; i < feed.size(); i++) {
                DailyTransaction tran = feed.get(i);
                int seq = i + 1;
                send(emitter, "pending", Map.of("seq", seq, "tranId", tran.tranId(),
                        "typeCode", tran.typeCode(), "amount", tran.amount().toPlainString(),
                        "description", tran.description()));

                JsonNode cobolResult = cobol.run(seq, tran.raw());
                long t0 = System.nanoTime();
                TransferOutcome javaResult = java.process(tran);
                long javaMicros = (System.nanoTime() - t0) / 1000;

                List<Comparison.Field> fields = Comparison.compare(cobolResult, javaResult);
                boolean match = !cobolResult.has("error") && Comparison.allMatch(fields);
                totals.add(match, cobolResult.path("fee"), javaResult.fee());

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seq", seq);
                row.put("tranId", tran.tranId());
                row.put("typeCode", tran.typeCode());
                row.put("description", tran.description());
                row.put("amount", tran.amount().toPlainString());
                row.put("book", javaResult.book() != null ? javaResult.book().strip()
                        : cobolResult.path("book").asText(""));
                row.put("srcAcctId", javaResult.srcAcctId());
                row.put("tgtAcctId", javaResult.tgtAcctId());
                row.put("skipReason", javaResult.skipReason());
                row.put("fields", fields);
                row.put("verdict", match ? "MATCH" : "DIFF");
                row.put("cobolRc", cobolResult.path("rc"));
                row.put("cobolSysout", cobolResult.path("sysout"));
                row.put("cobolError", cobolResult.path("error").asText(null));
                row.put("cobolMs", cobolResult.path("elapsedMs").asLong());
                row.put("javaMicros", javaMicros);
                row.put("totals", totals.snapshot());
                send(emitter, "txn", row);
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            }
            send(emitter, "done", totals.snapshot());
        }
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(mapper.writeValueAsString(data)));
        } catch (IOException e) {
            throw new IllegalStateException("client disconnected", e);
        }
    }

    private static final class Totals {
        private int count;
        private int matches;
        private int diffs;
        private BigDecimal cobolFees = new BigDecimal("0.00");
        private BigDecimal javaFees = new BigDecimal("0.00");

        void add(boolean match, JsonNode cobolFee, BigDecimal javaFee) {
            count++;
            if (match) {
                matches++;
            } else {
                diffs++;
            }
            if (cobolFee != null && !cobolFee.isMissingNode() && !cobolFee.isNull()) {
                cobolFees = cobolFees.add(new BigDecimal(cobolFee.asText()));
            }
            if (javaFee != null) {
                javaFees = javaFees.add(javaFee);
            }
        }

        Map<String, Object> snapshot() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", count);
            out.put("match", matches);
            out.put("diff", diffs);
            out.put("cobolFees", cobolFees.setScale(2).toPlainString());
            out.put("javaFees", javaFees.setScale(2).toPlainString());
            out.put("feeDelta", javaFees.subtract(cobolFees).setScale(2).toPlainString());
            return out;
        }
    }
}
