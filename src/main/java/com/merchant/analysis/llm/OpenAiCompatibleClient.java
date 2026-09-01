package com.merchant.analysis.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchant.analysis.config.LlmConfig;
import com.merchant.analysis.exception.LlmException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容客户端 —— 一切实现了 /v1/chat/completions 协议的服务都走这里：
 * OpenAI 官方、阿里云百炼（DashScope 兼容模式）、各类反代等，区别只在 base-url / api-key / model。
 *
 * 启用方式：LLM_PROVIDER=openai（或 application.yml 中 llm.provider=openai），并配置 LLM_API_KEY。
 * 推理模型耗时长，读超时给足 300s。
 */
@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai")
public class OpenAiCompatibleClient implements LlmClient {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    private final LlmConfig config;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OpenAiCompatibleClient(LlmConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(300_000);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(normalizeBaseUrl(config.getBaseUrl()))
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .build();
    }

    @Override
    public String chat(String prompt) {
        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.7
        );

        try {
            JsonNode root = restClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.isError(), (request, response) -> {
                        String detail = new String(response.getBody().readAllBytes());
                        throw new LlmException("LLM API error: " + response.getStatusCode() + " " + detail);
                    })
                    .body(JsonNode.class);

            if (root == null) {
                throw new LlmException("LLM API returned empty response");
            }

            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LlmException("LLM API returned no choices: " + root);
            }

            String content = choices.get(0).path("message").path("content").asText(null);
            if (content == null) {
                throw new LlmException("LLM API returned no content: " + root);
            }
            return content.trim();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM API call failed", e);
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
