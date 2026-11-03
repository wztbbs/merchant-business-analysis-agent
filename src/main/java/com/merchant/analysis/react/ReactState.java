package com.merchant.analysis.react;

import com.merchant.analysis.tool.ToolInvocation;
import java.util.*;

/** 只由主执行线程更新；预算在发起调用之前保存，恢复不重置。 */
public class ReactState {
    public ReactSettings limits;
    public long startedAt, deadline;
    public int modelCalls, toolCalls, callSequence, eventSequence, noProgress;
    public boolean finalized, answerAttempted;
    public ReactStatus status = ReactStatus.RUNNING;
    public String reason, answer, scene;
    public List<String> loaded = new ArrayList<>();
    public List<Call> calls = new ArrayList<>();
    public List<Observation> observations = new ArrayList<>();
    public Map<String, Integer> errors = new LinkedHashMap<>();
    public Set<String> inspected = new LinkedHashSet<>();
    public Map<String, List<Origin>> lineage = new LinkedHashMap<>();
    public Map<String, List<String>> origins = new LinkedHashMap<>();
    public Map<String, Map<String, Object>> originArguments = new LinkedHashMap<>();
    public List<HistoryBinding> history = new ArrayList<>();
    public List<Coverage> coverage = new ArrayList<>();
    public record Call(String callId, ToolInvocation invocation, String fingerprint, String reusedFrom) {}
    public record Observation(String action, String status, String callId, Object data, String error) {}
    public record HistoryBinding(String runId, String sourcePath, String aliasPath) {}
    public record Origin(String tool, String field, Map<String, Object> arguments) {}
    public record Coverage(int index, String status, List<String> evidence, String reason) {}
}
