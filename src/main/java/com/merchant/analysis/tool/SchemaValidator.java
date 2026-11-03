package com.merchant.analysis.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.regex.Pattern;

/** 本工程 schema 使用的 JSON Schema 子集；未知校验关键字不能静默忽略。 */
public final class SchemaValidator {
    private static final Set<String> KEYWORDS = Set.of("type", "properties", "required", "additionalProperties", "items",
            "anyOf", "oneOf", "enum", "const", "minimum", "maximum", "minItems", "maxItems", "minLength", "maxLength", "pattern", "description", "x-metrics", "x-scopes", "x-actions");
    private SchemaValidator() {}

    public static void checkSchema(JsonNode schema) {
        if (!schema.isObject() || schema.isEmpty()) throw new IllegalStateException("schema 必须为非空对象");
        schema.fieldNames().forEachRemaining(k -> {
            if (!KEYWORDS.contains(k)) throw new IllegalStateException("不支持的 schema 关键字: " + k);
        });
        if (schema.has("pattern")) Pattern.compile(schema.get("pattern").asText());
        if (schema.has("properties")) schema.get("properties").forEach(SchemaValidator::checkSchema);
        if (schema.has("items")) checkSchema(schema.get("items"));
        for (String k : new String[]{"oneOf", "anyOf"}) if (schema.has(k)) schema.get(k).forEach(SchemaValidator::checkSchema);
    }

    public static void validate(JsonNode value, JsonNode schema, String path) {
        if ((value.isDouble() || value.isFloat()) && !Double.isFinite(value.doubleValue()))
            fail(path, "必须为有限数值");
        if (schema.has("type")) {
            String type = schema.get("type").asText();
            boolean valid = switch (type) {
                case "object" -> value.isObject();
                case "array" -> value.isArray();
                case "string" -> value.isTextual();
                case "number" -> value.isNumber();
                case "integer" -> value.isIntegralNumber();
                case "boolean" -> value.isBoolean();
                case "null" -> value.isNull();
                default -> throw new IllegalStateException("未知 schema 类型: " + type);
            };
            if (!valid) fail(path, "需要 " + type);
        }
        if (schema.has("const") && !schema.get("const").equals(value)) fail(path, "取值必须为 " + schema.get("const"));
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode candidate : schema.get("enum")) if (candidate.equals(value)) found = true;
            if (!found) fail(path, "不在允许取值内");
        }
        for (String key : new String[]{"anyOf", "oneOf"}) {
            if (!schema.has(key)) continue;
            int matches = 0;
            for (JsonNode alternative : schema.get(key)) {
                try { validate(value, alternative, path); matches++; }
                catch (IllegalArgumentException ignored) { }
            }
            if (matches == 0 || key.equals("oneOf") && matches != 1) fail(path, "不符合 " + key + " 输入契约");
        }
        if (value.isObject()) {
            for (JsonNode key : schema.path("required")) if (!value.has(key.asText())) fail(path, "缺少字段 " + key.asText());
            value.fields().forEachRemaining(field -> {
                JsonNode child = schema.path("properties").get(field.getKey());
                if (child != null) validate(field.getValue(), child, path + "." + field.getKey());
                else if (schema.has("additionalProperties") && !schema.get("additionalProperties").asBoolean())
                    fail(path, "未知字段 " + field.getKey());
            });
        }
        if (value.isArray()) {
            bounds(value.size(), schema, "minItems", "maxItems", path);
            if (schema.has("items")) for (int i = 0; i < value.size(); i++) validate(value.get(i), schema.get("items"), path + "." + i);
        }
        if (value.isTextual()) {
            bounds(value.textValue().length(), schema, "minLength", "maxLength", path);
            if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").asText()).matcher(value.textValue()).find())
                fail(path, "字符串格式非法");
        }
        if (value.isNumber()) {
            if (schema.has("minimum") && value.decimalValue().compareTo(schema.get("minimum").decimalValue()) < 0) fail(path, "小于最小值");
            if (schema.has("maximum") && value.decimalValue().compareTo(schema.get("maximum").decimalValue()) > 0) fail(path, "大于最大值");
        }
    }
    private static void bounds(int size, JsonNode schema, String min, String max, String path) {
        if (schema.has(min) && size < schema.get(min).asInt() || schema.has(max) && size > schema.get(max).asInt()) fail(path, "长度超出范围");
    }
    private static void fail(String path, String reason) { throw new IllegalArgumentException(path + ": " + reason); }
}
