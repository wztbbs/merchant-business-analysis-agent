package com.merchant.analysis.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.merchant.analysis.execution.CapabilityCatalog;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 轻量元数据常驻，完整 YAML 定义在首次使用时读取并缓存。 */
@Component
public class ToolCatalog {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final CapabilityCatalog capabilities;
    private final Map<String, ToolDefinition> definitions = new ConcurrentHashMap<>();

    public ToolCatalog(CapabilityCatalog capabilities) { this.capabilities = capabilities; }

    public List<ToolSummary> summaries() {
        return capabilities.all().stream().map(d -> new ToolSummary(d.name(), d.description(), d.kind())).toList();
    }
    public boolean canSupply(String metric, String scope) {
        return metric != null && capabilities.all().stream().anyMatch(d -> d.kind().equals("business")
                && d.metrics().contains(metric) && d.scopes().contains(scope));
    }
    public ToolDefinition definition(String name) {
        capabilities.get(name); // 必须先通过白名单，不使用模型字符串拼任意资源路径。
        return definitions.computeIfAbsent(name, this::load);
    }
    public List<ToolDefinition> loadDefinitions(Collection<String> names) {
        names.forEach(capabilities::get);
        return names.stream().distinct().map(this::definition).toList();
    }
    private ToolDefinition load(String name) {
        JsonNode root = read("tools/" + name + ".yaml");
        if (!name.equals(root.path("name").asText())) throw new IllegalStateException("工具定义名称不一致: " + name);
        var metadata = capabilities.get(name);
        JsonNode common = read("tools/common.yaml");
        JsonNode input = expand(root.path("input_schema"), common);
        JsonNode output = expand(root.path("output_schema"), common);
        SchemaValidator.checkSchema(input);
        SchemaValidator.checkSchema(output);
        for (String field : metadata.params()) {
            if (!input.path("properties").has(field)) throw new IllegalStateException(name + " 缺少输入定义: " + field);
        }
        for (String field : metadata.produces()) {
            if (!output.path("properties").has(field)) throw new IllegalStateException(name + " 缺少输出定义: " + field);
        }
        return new ToolDefinition(name, root.path("description").asText(metadata.description()), metadata.kind(), input, output);
    }
    private static JsonNode read(String path) {
        try (InputStream stream = new ClassPathResource(path).getInputStream()) {
            return MAPPER.valueToTree(new Yaml().load(stream));
        } catch (Exception e) { throw new IllegalStateException("工具定义加载失败: " + path, e); }
    }
    private static JsonNode expand(JsonNode node, JsonNode common) {
        if (node.path("$ref").isTextual()) {
            String ref = node.path("$ref").asText();
            if (node.size() != 1 || !ref.startsWith("common:") || !common.has(ref.substring(7)))
                throw new IllegalStateException("非法工具 schema 引用: " + ref);
            return expand(common.get(ref.substring(7)), common);
        }
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            node.fields().forEachRemaining(e -> result.set(e.getKey(), expand(e.getValue(), common)));
            return result;
        }
        if (node.isArray()) {
            var result = MAPPER.createArrayNode();
            node.forEach(child -> result.add(expand(child, common)));
            return result;
        }
        return node.deepCopy();
    }
}
