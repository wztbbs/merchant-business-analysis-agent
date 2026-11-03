package com.merchant.analysis.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** 面向模型的完整契约，按名称加载；不暴露 Spring bean 等内部实现信息。 */
public record ToolDefinition(String name, String description, String kind,
                             @JsonProperty("input_schema") JsonNode inputSchema,
                             @JsonProperty("output_schema") JsonNode outputSchema) {
    public ToolDefinition {
        inputSchema = inputSchema.deepCopy();
        outputSchema = outputSchema.deepCopy();
    }
    @Override public JsonNode inputSchema() { return inputSchema.deepCopy(); }
    @Override public JsonNode outputSchema() { return outputSchema.deepCopy(); }
}
