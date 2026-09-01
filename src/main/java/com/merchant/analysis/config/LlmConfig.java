package com.merchant.analysis.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** OpenAI 兼容服务配置，llm.provider=openai 时生效（OpenAI 官方、阿里云百炼、各类反代均适用） */
@Data
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LlmConfig {

    /** OpenAI 兼容模式地址，如 https://api.openai.com / https://dashscope.aliyuncs.com/compatible-mode */
    private String baseUrl;

    private String apiKey;

    /** 模型名，如 gpt-4o / qwen-plus / qwen-max */
    private String model;
}
