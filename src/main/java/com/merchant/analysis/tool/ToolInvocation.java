package com.merchant.analysis.tool;

import java.util.List;
import java.util.Map;

/** 引用和时间已绑定的调用；执行器线程不负责发布结果。 */
public record ToolInvocation(String name, Map<String, Object> arguments, List<String> references) {
    public ToolInvocation {
        arguments = ToolExecution.snapshot(arguments);
        references = List.copyOf(references);
    }
}
