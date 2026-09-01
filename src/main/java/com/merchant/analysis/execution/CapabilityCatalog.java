package com.merchant.analysis.execution;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 能力目录：加载 capabilities.yaml，作为 plan 校验的供给白名单（capability 存在性 + produces 声明）。
 */
@Component
public class CapabilityCatalog {

    public record CapabilityDef(String name, String description, List<String> params,
                                List<String> produces, String executor) {
    }

    private final Map<String, CapabilityDef> defs = new ConcurrentHashMap<>();

    public CapabilityCatalog() {
        try (InputStream in = new ClassPathResource("capabilities.yaml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) root.get("capabilities");
            for (Map<String, Object> m : list) {
                CapabilityDef def = new CapabilityDef(
                        str(m, "name"), str(m, "description"),
                        strList(m, "params"), strList(m, "produces"), str(m, "executor"));
                defs.put(def.name(), def);
            }
        } catch (Exception e) {
            throw new IllegalStateException("加载 capabilities.yaml 失败", e);
        }
    }

    public boolean exists(String name) {
        return defs.containsKey(name);
    }

    public CapabilityDef get(String name) {
        CapabilityDef def = defs.get(name);
        if (def == null) {
            throw new IllegalArgumentException("能力目录中不存在: " + name);
        }
        return def;
    }

    /** capability → executor Spring bean 名 */
    public String executorBean(String name) {
        return get(name).executor();
    }

    @SuppressWarnings("unchecked")
    public static List<String> strList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? List.of() : (List<String>) v;
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }
}
