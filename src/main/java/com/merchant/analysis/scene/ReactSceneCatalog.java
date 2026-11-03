package com.merchant.analysis.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchant.analysis.execution.CapabilityCatalog;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import java.util.*;

/** 一个 YAML 一段文字指引；只向意图理解提供摘要，不编译执行步骤。 */
@Component
public class ReactSceneCatalog {
    public record Scene(String id, String name, String description, List<String> examples,
                        List<String> recommended_tools, String guidance) {
        public Scene {
            if (id == null || !id.matches("[a-z][a-z0-9_]*") || name == null || name.isBlank()
                    || description == null || description.isBlank() || guidance == null || guidance.isBlank())
                throw new IllegalArgumentException("文字场景需要合法 id、名称、描述和指引");
            examples = List.copyOf(examples);
            recommended_tools = List.copyOf(recommended_tools);
        }
    }
    public record Summary(String id, String name, String description, List<String> examples) {}
    private final Map<String, Scene> scenes;

    public ReactSceneCatalog(CapabilityCatalog capabilities) {
        Map<String, Scene> loaded = new TreeMap<>();
        try {
            var resources = new PathMatchingResourcePatternResolver().getResources("classpath*:react/scenarios/*.yaml");
            for (var resource : resources) {
                try (var stream = resource.getInputStream()) {
                    Scene scene = new ObjectMapper().convertValue(new Yaml().load(stream), Scene.class);
                    if (!(scene.id() + ".yaml").equals(resource.getFilename()) || loaded.putIfAbsent(scene.id(), scene) != null)
                        throw new IllegalArgumentException("文字场景名称重复或文件名不一致: " + scene.id());
                    for (String tool : scene.recommended_tools()) capabilities.get(tool);
                }
            }
            if (loaded.isEmpty()) throw new IllegalStateException("文字场景目录为空");
            scenes = Collections.unmodifiableMap(loaded);
        } catch (Exception e) { throw new IllegalStateException("文字场景加载失败", e); }
    }
    public List<Summary> summaries() {
        return scenes.values().stream().map(s -> new Summary(s.id(), s.name(), s.description(), s.examples())).toList();
    }
    public boolean contains(String id) { return id != null && scenes.containsKey(id); }
    public Scene get(String id) {
        Scene scene = scenes.get(id);
        if (scene == null) throw new IllegalArgumentException("未注册的文字场景: " + id);
        return scene;
    }
}
