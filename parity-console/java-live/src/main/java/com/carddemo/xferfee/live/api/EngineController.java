package com.carddemo.xferfee.live.api;

import java.math.RoundingMode;
import java.util.Map;

import com.carddemo.xferfee.live.engine.DailyTransaction;
import com.carddemo.xferfee.live.engine.EngineSeed;
import com.carddemo.xferfee.live.engine.EngineState;
import com.carddemo.xferfee.live.engine.TransferFeeEngine;
import com.carddemo.xferfee.live.engine.TransferRejectedException;
import com.carddemo.xferfee.live.engine.TransferResult;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/engine")
public class EngineController {

    private final TransferFeeEngine engine;

    public EngineController(TransferFeeEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/info")
    public Map<String, String> info() {
        return Map.of(
                "engine", ClassUtils.getUserClass(engine).getSimpleName(),
                "java", System.getProperty("java.version"),
                "rounding", engine.rounding().name());
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestBody EngineSeed seed) {
        engine.reset(seed);
        return Map.of("accounts", seed.accounts().size(), "xref", seed.xref().size(), "rules", seed.rules().size());
    }

    @PostMapping("/transactions")
    public TransferResult process(@RequestBody DailyTransaction transaction) {
        try {
            return engine.process(transaction);
        } catch (TransferRejectedException rejected) {
            return rejected.result();
        }
    }

    @GetMapping("/state")
    public EngineState state() {
        return engine.state();
    }

    @GetMapping("/rounding")
    public Map<String, String> rounding() {
        return Map.of("mode", engine.rounding().name());
    }

    @PutMapping("/rounding")
    public Map<String, String> setRounding(@RequestBody Map<String, String> body) {
        engine.setRounding(RoundingMode.valueOf(body.get("mode")));
        return rounding();
    }
}
