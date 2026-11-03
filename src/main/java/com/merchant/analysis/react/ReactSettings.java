package com.merchant.analysis.react;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "react")
public class ReactSettings {
    private int maxModelCalls = 12, maxToolCalls = 20, maxNoProgress = 3, maxSameErrors = 2;
    private int maxLoadedTools = 12, maxLoadBatch = 5, maxPromptChars = 120000;
    private long totalTimeoutMs = 90000, modelTimeoutMs = 20000, toolTimeoutMs = 5000, answerReserveMs = 10000;
    public int getMaxModelCalls() { return maxModelCalls; } public void setMaxModelCalls(int v) { maxModelCalls = v; }
    public int getMaxToolCalls() { return maxToolCalls; } public void setMaxToolCalls(int v) { maxToolCalls = v; }
    public int getMaxNoProgress() { return maxNoProgress; } public void setMaxNoProgress(int v) { maxNoProgress = v; }
    public int getMaxSameErrors() { return maxSameErrors; } public void setMaxSameErrors(int v) { maxSameErrors = v; }
    public int getMaxLoadedTools() { return maxLoadedTools; } public void setMaxLoadedTools(int v) { maxLoadedTools = v; }
    public int getMaxLoadBatch() { return maxLoadBatch; } public void setMaxLoadBatch(int v) { maxLoadBatch = v; }
    public int getMaxPromptChars() { return maxPromptChars; } public void setMaxPromptChars(int v) { maxPromptChars = v; }
    public long getTotalTimeoutMs() { return totalTimeoutMs; } public void setTotalTimeoutMs(long v) { totalTimeoutMs = v; }
    public long getModelTimeoutMs() { return modelTimeoutMs; } public void setModelTimeoutMs(long v) { modelTimeoutMs = v; }
    public long getToolTimeoutMs() { return toolTimeoutMs; } public void setToolTimeoutMs(long v) { toolTimeoutMs = v; }
    public long getAnswerReserveMs() { return answerReserveMs; } public void setAnswerReserveMs(long v) { answerReserveMs = v; }
    public ReactSettings snapshot() {
        ReactSettings s = new ReactSettings();
        s.maxModelCalls=maxModelCalls; s.maxToolCalls=maxToolCalls; s.maxNoProgress=maxNoProgress; s.maxSameErrors=maxSameErrors;
        s.maxLoadedTools=maxLoadedTools; s.maxLoadBatch=maxLoadBatch; s.maxPromptChars=maxPromptChars;
        s.totalTimeoutMs=totalTimeoutMs; s.modelTimeoutMs=modelTimeoutMs; s.toolTimeoutMs=toolTimeoutMs; s.answerReserveMs=answerReserveMs;
        if (maxModelCalls < 2 || maxToolCalls < 1 || maxNoProgress < 1 || maxSameErrors < 1 || maxLoadBatch < 1
                || maxLoadedTools < maxLoadBatch || maxPromptChars < 1000 || totalTimeoutMs <= answerReserveMs
                || answerReserveMs < 1 || modelTimeoutMs < 1 || toolTimeoutMs < 1)
            throw new IllegalArgumentException("ReAct 预算配置非法");
        return s;
    }
}
