package com.merchant.analysis.api;

import com.merchant.analysis.persistence.RunRepository;
import com.merchant.analysis.react.ReactRepository;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/react")
public class ReactTraceController {
    private final RunRepository runs;
    private final ReactRepository react;
    public ReactTraceController(RunRepository runs, ReactRepository react) { this.runs=runs; this.react=react; }
    @GetMapping("/{runId}")
    public Map<String, Object> trace(@PathVariable String runId) {
        var run = runs.findRun(runId);
        if (run == null || !"react".equals(run.scene())) throw new IllegalArgumentException("ReAct 分析不存在");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run); result.put("state", react.load(runId)); result.put("events", react.events(runId));
        return result;
    }
}
