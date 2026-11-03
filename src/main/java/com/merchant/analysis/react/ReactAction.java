package com.merchant.analysis.react;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchant.analysis.llm.LlmJson;
import java.util.*;

public record ReactAction(String action, List<String> names, String tool, Map<String, Object> arguments,
                          String reference, int offset, int limit, ReactStatus status,
                          List<ReactState.Coverage> requirements, String reason) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static ReactAction parse(String text) {
        if (text == null || text.length() > 32000) throw new IllegalArgumentException("动作响应过长或为空");
        JsonNode node = LlmJson.parse(text);
        if (!node.isObject()) throw new IllegalArgumentException("动作必须为 JSON 对象");
        String action = string(node, "action");
        Set<String> allowed = switch (action) {
            case "load_tools" -> Set.of("action", "names");
            case "call_tool" -> Set.of("action", "tool", "arguments");
            case "inspect_result" -> Set.of("action", "reference", "offset", "limit");
            case "finish" -> Set.of("action", "status", "requirements", "reason");
            default -> throw new IllegalArgumentException("未知 ReAct 动作");
        };
        node.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw new IllegalArgumentException("动作含未知字段: " + field); });
        List<String> names = List.of(); String tool = null, reference = null, reason = null;
        Map<String, Object> arguments = Map.of(); int offset = 0, limit = 10; ReactStatus status = null;
        List<ReactState.Coverage> requirements = new ArrayList<>();
        switch (action) {
            case "load_tools" -> names = strings(node.path("names"));
            case "call_tool" -> {
                tool = string(node, "tool");
                if (!node.path("arguments").isObject()) throw new IllegalArgumentException("arguments 必须为对象");
                arguments = JSON.convertValue(node.get("arguments"), new TypeReference<>() {});
            }
            case "inspect_result" -> {
                reference = string(node, "reference"); offset = integer(node, "offset"); limit = integer(node, "limit");
                if (offset < 0 || limit < 1 || limit > 20) throw new IllegalArgumentException("inspect offset>=0，limit为1到20");
            }
            case "finish" -> {
                status = ReactStatus.valueOf(string(node, "status"));
                if (!Set.of(ReactStatus.COMPLETED, ReactStatus.PARTIAL, ReactStatus.CAPABILITY_MISSING).contains(status))
                    throw new IllegalArgumentException("finish 只允许 COMPLETED/PARTIAL/CAPABILITY_MISSING");
                reason = string(node, "reason");
                if (!node.path("requirements").isArray()) throw new IllegalArgumentException("finish 需要 requirements 数组");
                for (JsonNode item : node.get("requirements")) {
                    if (!item.isObject()) throw new IllegalArgumentException("完成度必须为对象");
                    item.fieldNames().forEachRemaining(k -> { if (!Set.of("index", "status", "evidence", "reason").contains(k)) throw new IllegalArgumentException("未知完成度字段"); });
                    String completion = string(item, "status");
                    if (!Set.of("completed", "partial", "missing").contains(completion)) throw new IllegalArgumentException("未知子需求状态");
                    requirements.add(new ReactState.Coverage(integer(item, "index"), completion, strings(item.path("evidence")), string(item, "reason")));
                }
            }
        }
        return new ReactAction(action, names, tool, arguments, reference, offset, limit, status, List.copyOf(requirements), reason);
    }
    private static String string(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 1000) throw new IllegalArgumentException(field + " 必须为非空短字符串");
        return value.asText();
    }
    private static int integer(JsonNode node, String field) {
        if (!node.path(field).isIntegralNumber() || !node.path(field).canConvertToInt()) throw new IllegalArgumentException(field + " 必须为整数");
        return node.get(field).intValue();
    }
    private static List<String> strings(JsonNode value) {
        if (!value.isArray() || value.size() > 100) throw new IllegalArgumentException("需要不超过100项的字符串数组");
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 500) throw new IllegalArgumentException("数组项必须为非空短字符串");
            result.add(item.asText());
        }
        return List.copyOf(result);
    }
}
